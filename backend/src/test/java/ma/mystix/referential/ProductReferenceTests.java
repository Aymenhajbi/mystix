package ma.mystix.referential;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.zip.GZIPOutputStream;
import ma.mystix.TestcontainersConfiguration;
import ma.mystix.shared.Gtin;
import ma.mystix.tenant.CompanyService;
import ma.mystix.tenant.Ice;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.client.RestClient;

/**
 * Product reference (ADR-0020): import of an Open Facts style export (fixture written here, synthetic rows in the
 * real column layout), lookup by barcode, and the GTIN of Atelys products cross-checked against it.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@SuppressWarnings({"unchecked", "rawtypes"})
class ProductReferenceTests {

    @Value("${local.server.port}")
    int port;

    @Autowired
    ProductReference reference;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    CompanyService companies;

    RestClient http;
    UUID company;
    UUID other;

    // Synthetic codes with valid check digits: one in the GS1 Morocco range, one restricted circulation.
    static final String MOROCCO = "611999000001" + Gtin.checkDigit("611999000001");
    static final String RCN = "200000000123" + Gtin.checkDigit("200000000123");

    @BeforeAll
    void setUp(@TempDir Path dir) throws IOException {
        http = RestClient.builder().baseUrl("http://localhost:" + port)
                .defaultStatusHandler(status -> true, (request, response) -> { }).build();
        company = companies.register(new Ice("000000060000060"), "Epicerie Fictive SARL", null).id();
        other = companies.register(new Ice("000000061000061"), "Autre Epicerie Fictive SARL", null).id();

        Path export = dir.resolve(ProductReference.Source.OFF.file);
        String header = "code\turl\tcreator\tlast_modified_t\tproduct_name\tquantity\tbrands\tcategories_en\tcountries_en";
        List<String> rows = List.of(
                MOROCCO + "\tx\tx\t1760000000\tEau minérale (fictive)\t1,5 L\tMarque Fictive\tWaters\tMorocco",
                MOROCCO + "\tx\tx\t1700000000\tAncien nom (fictif)\t1 L\tMarque Fictive\tWaters\tMorocco",
                RCN + "\tx\tx\t\tProduit maison (fictif)\t\t\t\tMorocco",
                "6111035000431\tx\tx\t\tClé fausse\t\t\t\t",
                "\tx\tx\t\tSans code\t\t\t\t",
                "abc\tx\tx\t\tPas un code\t\t\t\t",
                "1234567890123\tx\tx\t\tNom avec\\backslash et \"guillemets\"\t\t\t\t");
        try (OutputStream out = new GZIPOutputStream(Files.newOutputStream(export))) {
            out.write((header + "\n" + String.join("\n", rows) + "\n").getBytes(StandardCharsets.UTF_8));
        }
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                        INSERT INTO product_reference_import (id, source, file_name, status, started_at, started_by)
                        VALUES (:id, 'OFF', 'test', 'RUNNING', :now, 'test')
                        """).param("id", id).param("now", OffsetDateTime.now()).update();
        reference.run(id, ProductReference.Source.OFF, export);
    }

    @Test
    void theImportKeepsValidCodesOnceAndCountsTheRest() {
        ProductReference.Import done = reference.imports().stream()
                .filter(i -> "test".equals(i.fileName())).findFirst().orElseThrow();
        assertThat(done.status()).isEqualTo("DONE");
        assertThat(done.rowsRead()).isEqualTo(7);
        assertThat(done.rowsNoCode()).isEqualTo(1);
        // "6111035000431", "abc" and "1234567890123" fail the length or check digit test.
        assertThat(done.rowsBadCheck()).isEqualTo(3);
        assertThat(done.rowsImported()).isEqualTo(2);
        assertThat(done.fileSha256()).hasSize(64);

        // Duplicate code in the export: the most recently modified row wins.
        ProductReference.Lookup hit = reference.lookup(MOROCCO);
        assertThat(hit.valid()).isTrue();
        assertThat(hit.prefix()).isEqualTo("GS1_MOROCCO");
        assertThat(hit.matches()).singleElement().satisfies(m -> {
            assertThat(m.name()).isEqualTo("Eau minérale (fictive)");
            assertThat(m.quantity()).isEqualTo("1,5 L");
            assertThat(m.sourceLabel()).isEqualTo("Open Food Facts");
        });
        assertThat(hit.attribution()).contains("ODbL");
        assertThat(reference.lookup(RCN).prefix()).isEqualTo("RESTRICTED_CIRCULATION");
        assertThat(reference.lookup("6111035000431").valid()).isFalse();
    }

    @Test
    void aProductCarriesItsGtinAndAScanFindsItWithTheReference() {
        Map created = post("/api/v1/wms/products", company, Map.of("sku", "EAU-15", "name", "Eau 1,5 L (fictif)",
                "category", "FINISHED", "baseUnit", "U", "gtin", MOROCCO)).getBody();
        assertThat(created).containsEntry("gtin", "0" + MOROCCO);

        // A wrong check digit is refused, never corrected.
        assertThat(post("/api/v1/wms/products", company, Map.of("sku", "EAU-X", "name", "X", "category", "FINISHED",
                "baseUnit", "U", "gtin", "6111035000431")).getBody()).containsEntry("errorCode", "GTIN_INVALID");
        // The same GTIN twice in one environment is refused; another environment may use it.
        assertThat(post("/api/v1/wms/products", company, Map.of("sku", "EAU-BIS", "name", "Bis", "category",
                "FINISHED", "baseUnit", "U", "gtin", MOROCCO)).getBody()).containsEntry("errorCode", "ATELYS_CODE_TAKEN");
        assertThat(post("/api/v1/wms/products", other, Map.of("sku", "EAU-15", "name", "Eau (fictif)", "category",
                "FINISHED", "baseUnit", "U", "gtin", MOROCCO)).getStatusCode()).isEqualTo(HttpStatus.CREATED);

        Map scan = get("/api/v1/wms/products/by-gtin/" + MOROCCO, company);
        assertThat((Map) scan.get("product")).containsEntry("sku", "EAU-15").containsEntry("id", created.get("id"));
        assertThat((List) ((Map) scan.get("reference")).get("matches")).hasSize(1);

        // Known in the reference but not in this environment: no product, the reference still answers.
        Map unknownHere = get("/api/v1/wms/products/by-gtin/" + RCN, company);
        assertThat(unknownHere.get("product")).isNull();
        assertThat((Map) unknownHere.get("reference")).containsEntry("valid", true);
        assertThat((Map) get("/api/v1/wms/products/by-gtin/123", company).get("reference")).containsEntry("valid", false);

        // Set and remove the GTIN of an existing product; the search finds a product by its GTIN.
        Map plain = post("/api/v1/wms/products", company, Map.of("sku", "SAC-01", "name", "Sac (fictif)",
                "category", "CONSUMABLE", "baseUnit", "U")).getBody();
        Map set = put("/api/v1/wms/products/" + plain.get("id") + "/gtin", company, Map.of("gtin", RCN));
        assertThat((Map) set.get("product")).containsEntry("gtin", "0" + RCN);
        assertThat((List<Map>) http.get().uri("/api/v1/wms/products?q=" + RCN).header("X-Mystix-Company-Id",
                company.toString()).retrieve().body(List.class)).extracting(p -> p.get("sku")).containsExactly("SAC-01");
        assertThat((Map) put("/api/v1/wms/products/" + plain.get("id") + "/gtin", company, Map.of("gtin", ""))
                .get("product")).containsEntry("gtin", null);
    }

    @Test
    void theLookupAndStatusAreOpenToSignedInUsers() {
        Map lookup = http.get().uri("/api/v1/referential/products/" + MOROCCO).retrieve().body(Map.class);
        assertThat(lookup).containsEntry("display", MOROCCO);
        Map status = http.get().uri("/api/v1/referential/products/status").retrieve().body(Map.class);
        assertThat((Map) status.get("counts")).containsKey("OFF");
    }

    private ResponseEntity<Map> post(String uri, UUID companyId, Object body) {
        return http.post().uri(uri).header("X-Mystix-Company-Id", companyId.toString())
                .contentType(MediaType.APPLICATION_JSON).body(body).retrieve().toEntity(Map.class);
    }

    private Map put(String uri, UUID companyId, Object body) {
        return http.put().uri(uri).header("X-Mystix-Company-Id", companyId.toString())
                .contentType(MediaType.APPLICATION_JSON).body(body).retrieve().body(Map.class);
    }

    private Map get(String uri, UUID companyId) {
        return http.get().uri(uri).header("X-Mystix-Company-Id", companyId.toString()).retrieve().body(Map.class);
    }
}
