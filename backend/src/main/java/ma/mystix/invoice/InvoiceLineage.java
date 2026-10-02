package ma.mystix.invoice;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import ma.mystix.format.ubl.UblMappingSpec;
import ma.mystix.format.ubl.UblXPath;
import org.springframework.stereotype.Service;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Field lineage of a stored invoice: for each field of {@link UblMappingSpec}, the value read in the stored RAW
 * request, in the stored canonical model and in the stored UBL output. Values are read from the artefacts, never
 * recomputed, so the view shows exactly what Mystix received and produced.
 */
@Service
public class InvoiceLineage {

    private static final Pattern INDEX = Pattern.compile("^([A-Za-z]+)\\[(\\d+)]$");

    /** Decimals read as BigDecimal so "125.50" stays "125.50". */
    private final JsonMapper json = JsonMapper.builder()
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .build();

    private final InvoiceService invoices;

    InvoiceLineage(InvoiceService invoices) {
        this.invoices = invoices;
    }

    public record Value(String path, String value) {
    }

    public record Row(String group, Integer line, String term, String label, UblMappingSpec.Kind kind, String rule,
                      Value request, Value canonical, Value target) {
    }

    public List<Row> of(UUID companyId, UUID invoiceId) {
        JsonNode raw = readJson(invoices.artifact(companyId, invoiceId, ArtifactKind.RAW));
        JsonNode canonical = readJson(invoices.artifact(companyId, invoiceId, ArtifactKind.CANONICAL));
        UblXPath out = new UblXPath(invoices.artifact(companyId, invoiceId, ArtifactKind.OUT));
        int lines = canonical == null ? 0 : canonical.path("invoice").path("lines").size();

        List<Row> rows = new ArrayList<>();
        for (UblMappingSpec.Field f : UblMappingSpec.FIELDS) {
            if ("line".equals(f.group())) {
                for (int i = 0; i < lines; i++) {
                    rows.add(row(f, i, raw, canonical, out));
                }
            } else {
                rows.add(row(f, null, raw, canonical, out));
            }
        }
        return rows;
    }

    private Row row(UblMappingSpec.Field f, Integer line, JsonNode raw, JsonNode canonical, UblXPath out) {
        String request = indexed(f.request(), line);
        String canon = indexed(f.canonical(), line);
        String target = indexed(f.target(), line);
        return new Row(f.group(), line, f.term(), f.label(), f.kind(), f.rule(),
                request == null ? null : new Value(request, valueAt(raw, request)),
                canon == null ? null : new Value(canon, valueAt(canonical, canon)),
                target == null ? null : new Value(target, out.text(target)));
    }

    private static String indexed(String path, Integer line) {
        if (path == null || line == null) {
            return path;
        }
        return path.replace("{i}", String.valueOf(line)).replace("{n}", String.valueOf(line + 1));
    }

    /** Value at a dotted path with optional [index] segments; {@code null} when absent or not a scalar. */
    private static String valueAt(JsonNode root, String path) {
        JsonNode node = root;
        for (String segment : path.split("\\.")) {
            if (node == null) {
                return null;
            }
            Matcher m = INDEX.matcher(segment);
            node = m.matches() ? node.path(m.group(1)).path(Integer.parseInt(m.group(2))) : node.path(segment);
        }
        if (node == null || node.isMissingNode() || node.isNull() || node.isContainer()) {
            return null;
        }
        return node.isBigDecimal() ? node.decimalValue().toPlainString() : node.asString();
    }

    private JsonNode readJson(byte[] content) {
        try {
            return json.readTree(content);
        } catch (RuntimeException e) {
            return null;
        }
    }
}
