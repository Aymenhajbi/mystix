package ma.mystix.referential;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.zip.GZIPInputStream;
import javax.sql.DataSource;
import ma.mystix.shared.Gtin;
import ma.mystix.shared.error.ErrorCode;
import ma.mystix.shared.error.MystixException;
import org.postgresql.PGConnection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * Product reference built from the open databases of the Open Food Facts project (ODbL). Imports read the nightly
 * CSV exports (gzip, tab separated) from a server directory, keep only rows whose code is a GTIN with a valid check
 * digit, and upsert them by batches through COPY. Lookups answer "is this barcode a known product, and which?".
 */
@Service
public class ProductReference {

    private static final Logger log = LoggerFactory.getLogger(ProductReference.class);
    private static final int BATCH = 20_000;

    /** The three sources, their export file and the attribution they require. */
    public enum Source {
        OFF("en.openfoodfacts.org.products.csv.gz", "Open Food Facts", "https://world.openfoodfacts.org"),
        OBF("en.openbeautyfacts.org.products.csv.gz", "Open Beauty Facts", "https://world.openbeautyfacts.org"),
        OPF("en.openproductsfacts.org.products.csv.gz", "Open Products Facts", "https://world.openproductsfacts.org");

        public final String file;
        public final String label;
        public final String site;

        Source(String file, String label, String site) {
            this.file = file;
            this.label = label;
            this.site = site;
        }
    }

    public static final String LICENCE = "ODbL 1.0 (base), DbCL 1.0 (contenu)";

    public record Match(String source, String sourceLabel, String code, String name, String brands, String quantity,
                        String categories, String countries, OffsetDateTime lastModified, String url) {
    }

    public record Lookup(String input, boolean valid, String gtin, String display, Integer length, String prefix,
                         List<Match> matches, String attribution) {
    }

    public record Import(UUID id, String source, String fileName, String fileSha256, String status, long rowsRead,
                         long rowsImported, long rowsNoCode, long rowsBadCheck, String error,
                         OffsetDateTime startedAt, OffsetDateTime finishedAt, String startedBy) {
    }

    private final JdbcClient jdbc;
    private final DataSource dataSource;
    private final Clock clock;
    private final Path directory;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "product-reference-import");
        t.setDaemon(true);
        return t;
    });

    ProductReference(JdbcClient jdbc, DataSource dataSource, Clock clock,
                     @Value("${mystix.product-reference.directory:}") String directory) {
        this.jdbc = jdbc;
        this.dataSource = dataSource;
        this.clock = clock;
        this.directory = directory.isBlank() ? null : Path.of(directory);
    }

    public Lookup lookup(String input) {
        var parsed = Gtin.parse(input);
        if (parsed.isEmpty()) {
            return new Lookup(input, false, null, null, null, null, List.of(), attribution(List.of()));
        }
        Gtin g = parsed.get();
        List<Match> matches = jdbc.sql("SELECT * FROM product_reference WHERE gtin = :g ORDER BY source")
                .param("g", g.gtin14())
                .query((rs, n) -> {
                    Source s = Source.valueOf(rs.getString("source"));
                    return new Match(s.name(), s.label, rs.getString("code"), rs.getString("name"),
                            rs.getString("brands"), rs.getString("quantity"), rs.getString("categories"),
                            rs.getString("countries"), rs.getObject("last_modified", OffsetDateTime.class),
                            s.site + "/product/" + rs.getString("code"));
                })
                .list();
        return new Lookup(input, true, g.gtin14(), g.display(), g.length(), g.prefix().name(), matches,
                attribution(matches));
    }

    private static String attribution(List<Match> matches) {
        return "Données : contributeurs Open Food Facts, Open Beauty Facts, Open Products Facts — " + LICENCE;
    }

    public List<Import> imports() {
        return jdbc.sql("SELECT * FROM product_reference_import ORDER BY started_at DESC LIMIT 30")
                .query((rs, n) -> new Import(rs.getObject("id", UUID.class), rs.getString("source"),
                        rs.getString("file_name"), rs.getString("file_sha256"), rs.getString("status"),
                        rs.getLong("rows_read"), rs.getLong("rows_imported"), rs.getLong("rows_no_code"),
                        rs.getLong("rows_bad_check"), rs.getString("error"),
                        rs.getObject("started_at", OffsetDateTime.class),
                        rs.getObject("finished_at", OffsetDateTime.class), rs.getString("started_by")))
                .list();
    }

    public Map<String, Long> counts() {
        return jdbc.sql("SELECT source, count(*) AS n FROM product_reference GROUP BY source")
                .query((rs, n) -> Map.entry(rs.getString("source"), rs.getLong("n"))).list().stream()
                .collect(java.util.stream.Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    /** Starts the import of one source from the configured directory; returns at once with the import row. */
    public Import start(Source source, String actor) {
        if (directory == null) {
            throw new MystixException(ErrorCode.VALIDATION_FAILED, "mystix.product-reference.directory is not set");
        }
        Path file = directory.resolve(source.file);
        if (!Files.isRegularFile(file)) {
            throw new MystixException(ErrorCode.VALIDATION_FAILED, "Export not found: " + source.file);
        }
        boolean running = jdbc.sql("SELECT EXISTS (SELECT 1 FROM product_reference_import WHERE status = 'RUNNING')")
                .query(Boolean.class).single();
        if (running) {
            throw new MystixException(ErrorCode.STATUS_TRANSITION_INVALID, "An import is already running");
        }
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                        INSERT INTO product_reference_import (id, source, file_name, status, started_at, started_by)
                        VALUES (:id, :s, :f, 'RUNNING', :now, :actor)
                        """)
                .param("id", id).param("s", source.name()).param("f", source.file)
                .param("now", OffsetDateTime.now(clock)).param("actor", actor).update();
        worker.submit(() -> run(id, source, file));
        return imports().stream().filter(i -> i.id().equals(id)).findFirst().orElseThrow();
    }

    /** Synchronous import, used by tests and by {@link #start}. */
    void run(UUID importId, Source source, Path file) {
        long[] stats = new long[4]; // read, imported, no code, bad check digit
        try (Connection c = dataSource.getConnection()) {
            c.setAutoCommit(false);
            try (Statement st = c.createStatement()) {
                st.execute("""
                        CREATE TEMP TABLE IF NOT EXISTS product_reference_stage (
                            gtin CHAR(14), code VARCHAR(32), name VARCHAR(300), brands VARCHAR(300),
                            quantity VARCHAR(100), categories VARCHAR(500), countries VARCHAR(500),
                            last_modified TIMESTAMPTZ) ON COMMIT DELETE ROWS""");
            }
            c.commit();
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            try (InputStream raw = new java.security.DigestInputStream(Files.newInputStream(file), sha);
                 BufferedReader in = new BufferedReader(new InputStreamReader(new GZIPInputStream(raw, 1 << 16),
                         StandardCharsets.UTF_8), 1 << 16)) {
                String header = in.readLine();
                if (header == null) {
                    throw new IOException("Empty export");
                }
                Columns col = new Columns(header.split("\t", -1));
                StringBuilder batch = new StringBuilder(BATCH * 200);
                int inBatch = 0;
                String line;
                while ((line = in.readLine()) != null) {
                    stats[0]++;
                    String[] f = line.split("\t", -1);
                    String code = col.get(f, "code");
                    if (code == null || code.isBlank()) {
                        stats[2]++;
                        continue;
                    }
                    var gtin = Gtin.parse(code);
                    if (gtin.isEmpty()) {
                        stats[3]++;
                        continue;
                    }
                    row(batch, gtin.get().gtin14(), cut(code, 32), cut(col.get(f, "product_name"), 300),
                            cut(col.get(f, "brands"), 300), cut(col.get(f, "quantity"), 100),
                            cut(col.get(f, "categories_en"), 500), cut(col.get(f, "countries_en"), 500),
                            epoch(col.get(f, "last_modified_t")));
                    if (++inBatch == BATCH) {
                        stats[1] += flush(c, batch, importId, source);
                        batch.setLength(0);
                        inBatch = 0;
                        progress(importId, stats);
                    }
                }
                if (inBatch > 0) {
                    stats[1] += flush(c, batch, importId, source);
                }
                // Drain the compressed stream so the SHA-256 covers the whole file.
                raw.transferTo(java.io.OutputStream.nullOutputStream());
            }
            jdbc.sql("""
                            UPDATE product_reference_import SET status = 'DONE', finished_at = :now, file_sha256 = :sha,
                                rows_read = :r, rows_imported = :i, rows_no_code = :n, rows_bad_check = :b
                            WHERE id = :id
                            """)
                    .param("now", OffsetDateTime.now(clock)).param("sha", HexFormat.of().formatHex(sha.digest()))
                    .param("r", stats[0]).param("i", stats[1]).param("n", stats[2]).param("b", stats[3])
                    .param("id", importId).update();
            log.info("Product reference {} imported: {} rows read, {} upserted", source, stats[0], stats[1]);
        } catch (Exception e) {
            log.warn("Product reference import {} failed after {} rows", source, stats[0], e);
            jdbc.sql("""
                            UPDATE product_reference_import SET status = 'FAILED', finished_at = :now, error = :e,
                                rows_read = :r, rows_imported = :i
                            WHERE id = :id
                            """)
                    .param("now", OffsetDateTime.now(clock)).param("e", cut(e.getClass().getSimpleName() + ": "
                            + e.getMessage(), 500))
                    .param("r", stats[0]).param("i", stats[1]).param("id", importId).update();
        }
    }

    private long flush(Connection c, StringBuilder batch, UUID importId, Source source) throws SQLException,
            IOException {
        c.unwrap(PGConnection.class).getCopyAPI().copyIn(
                "COPY product_reference_stage (gtin, code, name, brands, quantity, categories, countries, "
                        + "last_modified) FROM STDIN", new StringReader(batch.toString()));
        long upserted;
        try (var ps = c.prepareStatement("""
                INSERT INTO product_reference (gtin, source, code, name, brands, quantity, categories, countries,
                                               last_modified, import_id)
                SELECT DISTINCT ON (gtin) gtin, ?, code, name, brands, quantity, categories, countries, last_modified, ?
                FROM product_reference_stage
                ORDER BY gtin, last_modified DESC NULLS LAST
                ON CONFLICT (gtin, source) DO UPDATE SET code = EXCLUDED.code, name = EXCLUDED.name,
                    brands = EXCLUDED.brands, quantity = EXCLUDED.quantity, categories = EXCLUDED.categories,
                    countries = EXCLUDED.countries, last_modified = EXCLUDED.last_modified,
                    import_id = EXCLUDED.import_id
                """)) {
            ps.setString(1, source.name());
            ps.setObject(2, importId);
            upserted = ps.executeUpdate();
        }
        c.commit(); // ON COMMIT DELETE ROWS empties the stage for the next batch.
        return upserted;
    }

    private void progress(UUID importId, long[] stats) {
        jdbc.sql("UPDATE product_reference_import SET rows_read = :r, rows_imported = :i WHERE id = :id")
                .param("r", stats[0]).param("i", stats[1]).param("id", importId).update();
    }

    private static void row(StringBuilder b, String... values) {
        for (int i = 0; i < values.length; i++) {
            if (i > 0) {
                b.append('\t');
            }
            String v = values[i];
            if (v == null) {
                b.append("\\N");
                continue;
            }
            for (int k = 0; k < v.length(); k++) {
                char ch = v.charAt(k);
                switch (ch) {
                    case '\\' -> b.append("\\\\");
                    case '\t' -> b.append("\\t");
                    case '\n' -> b.append("\\n");
                    case '\r' -> b.append("\\r");
                    default -> {
                        if (ch >= ' ') {
                            b.append(ch);
                        }
                    }
                }
            }
        }
        b.append('\n');
    }

    private static String epoch(String seconds) {
        if (seconds == null || seconds.isBlank() || !seconds.chars().allMatch(Character::isDigit)) {
            return null;
        }
        return OffsetDateTime.ofInstant(Instant.ofEpochSecond(Long.parseLong(seconds)), ZoneOffset.UTC).toString();
    }

    private static String cut(String s, int max) {
        if (s == null) {
            return null;
        }
        String t = s.strip();
        if (t.isEmpty()) {
            return null;
        }
        return t.length() > max ? t.substring(0, max) : t;
    }

    /** Column positions from the header line; exports may add or reorder columns. */
    private static final class Columns {
        private final Map<String, Integer> index = new java.util.HashMap<>();

        Columns(String[] header) {
            for (int i = 0; i < header.length; i++) {
                index.putIfAbsent(header[i].strip(), i);
            }
        }

        String get(String[] row, String name) {
            Integer i = index.get(name);
            return i == null || i >= row.length ? null : row[i];
        }
    }
}
