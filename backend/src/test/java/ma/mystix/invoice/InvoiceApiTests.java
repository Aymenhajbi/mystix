package ma.mystix.invoice;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import ma.mystix.TestcontainersConfiguration;
import ma.mystix.shared.Sha256;
import ma.mystix.tenant.CompanyService;
import ma.mystix.tenant.Ice;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClient;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "mystix.clearance.simulated.reject-numbers-matching=FA-SIMREJECT-.*")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class InvoiceApiTests {

    private static final String REFERENCE_NUMBER = "FA-2026-000123";

    @Value("${local.server.port}")
    int port;

    @Autowired
    CompanyService companies;

    RestClient http;
    String validRequest;
    UUID sellerCompany;
    UUID otherCompany;

    @BeforeAll
    void registerCompanies() {
        // Same ICE as the seller of the reference fixture.
        sellerCompany = companies.register(new Ice("000000001000011"), "Fournisseur Synthetique SARL", null).id();
        otherCompany = companies.register(new Ice("000000003000033"), "Autre Societe Synthetique", null).id();
    }

    @BeforeEach
    void setUp() throws IOException {
        http = RestClient.builder()
                .baseUrl("http://localhost:" + port)
                .defaultStatusHandler(status -> true, (request, response) -> { })
                .build();
        validRequest = new String(resource("fixtures/api/invoice-basic.request.json"), StandardCharsets.UTF_8);
    }

    @Test
    void acceptsStoresAndReplaysTheReferenceInvoice() throws IOException {
        ResponseEntity<byte[]> first = submit(sellerCompany, validRequest, byte[].class);

        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(first.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_XML);
        assertThat(first.getHeaders().getFirst("X-Mystix-Replayed")).isEqualTo("false");
        assertThat(first.getHeaders().getFirst("X-Mystix-Canonical-Version")).isEqualTo("1.0");
        assertThat(first.getHeaders().getFirst("X-Mystix-En16931-Artefacts")).isEqualTo("1.3.16");
        assertThat(first.getBody()).isEqualTo(resource("fixtures/ubl/invoice-basic.expected.xml"));
        String id = first.getHeaders().getFirst("X-Mystix-Invoice-Id");
        assertThat(first.getHeaders().getLocation()).hasToString("/api/v1/invoices/" + id);

        // Same invoice again, reformatted: same canonical content, so a replay of the stored result.
        ResponseEntity<byte[]> replay = submit(sellerCompany, validRequest.replace("\n", "\r\n  "), byte[].class);

        assertThat(replay.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(replay.getHeaders().getFirst("X-Mystix-Replayed")).isEqualTo("true");
        assertThat(replay.getHeaders().getFirst("X-Mystix-Invoice-Id")).isEqualTo(id);
        assertThat(replay.getBody()).isEqualTo(first.getBody());

        // Simulated clearance: cleared once, the replay returns the same reference without a new clearance.
        assertThat(first.getHeaders().getFirst("X-Mystix-Status")).isEqualTo("CLEARED");
        assertThat(first.getHeaders().getFirst("X-Mystix-Clearance-Simulated")).isEqualTo("true");
        String reference = first.getHeaders().getFirst("X-Mystix-Clearance-Reference");
        assertThat(reference).startsWith("SIMULATED-");
        assertThat(replay.getHeaders().getFirst("X-Mystix-Clearance-Reference")).isEqualTo(reference);

        ResponseEntity<Map> stored = http.get().uri("/api/v1/invoices/{id}", id)
                .header("X-Mystix-Company-Id", sellerCompany.toString())
                .retrieve().toEntity(Map.class);
        assertThat(stored.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(stored.getBody())
                .containsEntry("number", REFERENCE_NUMBER)
                .containsEntry("status", "CLEARED")
                .containsEntry("canonicalVersion", "1.0");
        assertThat((Map<String, Object>) stored.getBody().get("clearance"))
                .containsEntry("reference", reference)
                .containsEntry("simulated", true);
        assertThat((List<Map<String, Object>>) stored.getBody().get("history"))
                .extracting(e -> e.get("status"))
                .containsExactly("VALIDATED", "CLEARED");
        assertThat((List<Map<String, Object>>) stored.getBody().get("artifacts"))
                .extracting(a -> a.get("kind"))
                .containsExactly("RAW", "CANONICAL", "OUT");

        ResponseEntity<byte[]> raw = artifact(sellerCompany, id, "RAW");
        assertThat(raw.getBody()).isEqualTo(validRequest.getBytes(StandardCharsets.UTF_8));
        ResponseEntity<byte[]> out = artifact(sellerCompany, id, "OUT");
        assertThat(out.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_XML);
        assertThat(out.getBody()).isEqualTo(first.getBody());
        assertThat((List<Map<String, Object>>) stored.getBody().get("artifacts"))
                .filteredOn(a -> "OUT".equals(a.get("kind")))
                .singleElement()
                .satisfies(a -> assertThat(a).containsEntry("sha256", Sha256.hex(first.getBody())));
    }

    @Test
    void simulatedClearanceRejectionIsStoredAndTraced() {
        ResponseEntity<byte[]> response = submit(sellerCompany, withNumber("FA-SIMREJECT-1"), byte[].class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getHeaders().getFirst("X-Mystix-Status")).isEqualTo("CLEARANCE_REJECTED");
        assertThat(response.getHeaders().getFirst("X-Mystix-Clearance-Simulated")).isEqualTo("true");
        assertThat(response.getHeaders().getFirst("X-Mystix-Clearance-Reference")).isNull();

        String id = response.getHeaders().getFirst("X-Mystix-Invoice-Id");
        ResponseEntity<Map> stored = http.get().uri("/api/v1/invoices/{id}", id)
                .header("X-Mystix-Company-Id", sellerCompany.toString())
                .retrieve().toEntity(Map.class);
        assertThat(stored.getBody()).containsEntry("status", "CLEARANCE_REJECTED");
        assertThat((List<Map<String, Object>>) stored.getBody().get("history"))
                .last()
                .satisfies(e -> assertThat(e)
                        .containsEntry("status", "CLEARANCE_REJECTED")
                        .hasEntrySatisfying("detail", d -> assertThat((String) d).startsWith("SIMULATED")));
    }

    @Test
    void sameNumberWithDifferentContentIsAConflict() {
        String original = withNumber("FA-CONFLICT-1");
        assertThat(submit(sellerCompany, original, byte[].class).getStatusCode()).isEqualTo(HttpStatus.CREATED);

        ResponseEntity<Map> conflict = submit(sellerCompany,
                original.replace("Synthetic test invoice & fixture", "Changed note"), Map.class);

        assertThat(conflict.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(conflict.getBody()).containsEntry("errorCode", "INVOICE_NUMBER_CONFLICT");
    }

    @Test
    void aCompanyCannotReadAnotherCompanysInvoice() {
        ResponseEntity<byte[]> created = submit(sellerCompany, withNumber("FA-ISOLATION-1"), byte[].class);
        String id = created.getHeaders().getFirst("X-Mystix-Invoice-Id");

        ResponseEntity<Map> asOther = http.get().uri("/api/v1/invoices/{id}", id)
                .header("X-Mystix-Company-Id", otherCompany.toString())
                .retrieve().toEntity(Map.class);
        ResponseEntity<Map> artifactAsOther = http.get().uri("/api/v1/invoices/{id}/artifacts/RAW", id)
                .header("X-Mystix-Company-Id", otherCompany.toString())
                .retrieve().toEntity(Map.class);

        assertThat(asOther.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(asOther.getBody()).containsEntry("errorCode", "INVOICE_NOT_FOUND");
        assertThat(artifactAsOther.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void sellerIceMustBeTheSubmittingCompany() {
        ResponseEntity<Map> response = submit(otherCompany, withNumber("FA-WRONG-SELLER"), Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).containsEntry("errorCode", "INVOICE_REJECTED");
        assertThat(fields(response)).containsExactly("seller.ice");
    }

    @Test
    void companyHeaderIsRequiredAndMustExist() {
        ResponseEntity<Map> missing = http.post().uri("/api/v1/invoices")
                .contentType(MediaType.APPLICATION_JSON)
                .body(validRequest)
                .retrieve().toEntity(Map.class);
        ResponseEntity<Map> unknown = submit(UUID.randomUUID(), withNumber("FA-UNKNOWN-CO"), Map.class);

        assertThat(missing.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(fields(missing)).containsExactly("X-Mystix-Company-Id");
        assertThat(unknown.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(unknown.getBody()).containsEntry("errorCode", "COMPANY_NOT_FOUND");
    }

    @Test
    void reportsCanonicalViolationsWithTheirJsonPath() {
        String request = withNumber("FA-BAD-GS1")
                .replace("\"gln\": \"6110000000017\"", "\"gln\": \"6110000000018\"")
                .replace("\"gtin\": \"6110000000109\"", "\"gtin\": \"6110000000100\"");

        ResponseEntity<Map> response = submit(sellerCompany, request, Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody())
                .containsEntry("errorCode", "INVOICE_REJECTED")
                .containsEntry("stage", "MAPPING");
        assertThat(fields(response)).containsExactlyInAnyOrder("seller", "lines[0]");
    }

    @Test
    void reportsMissingAndNestedFields() {
        String request = validRequest
                .replace("\"number\": \"FA-2026-000123\",", "")
                .replace("\"countryCode\": \"MA\" }\n  },\n  \"buyer\"", "\"countryCode\": \"Maroc\" }\n  },\n  \"buyer\"");

        ResponseEntity<Map> response = submit(sellerCompany, request, Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).containsEntry("errorCode", "VALIDATION_FAILED");
        assertThat(fields(response)).contains("number", "seller.address.countryCode");
    }

    @Test
    void rejectsUnreadableJson() {
        ResponseEntity<Map> response = submit(sellerCompany, "{ not json", Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).containsEntry("errorCode", "VALIDATION_FAILED");
    }

    @Test
    void returnsEn16931RuleViolations() {
        // EN 16931 BR-S-05: a standard rated (S) line must have a VAT rate greater than zero.
        String request = withNumber("FA-BR-S-05").replace(
                "\"vat\": { \"category\": \"S\", \"ratePercent\": \"10.00\" }",
                "\"vat\": { \"category\": \"S\", \"ratePercent\": 0 }");

        ResponseEntity<Map> response = submit(sellerCompany, request, Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_JSON);
        assertThat(response.getBody())
                .containsEntry("errorCode", "INVOICE_RULES_VIOLATED")
                .containsEntry("stage", "VALIDATION");
        assertThat((List<Map<String, Object>>) response.getBody().get("ruleViolations"))
                .anySatisfy(v -> assertThat(v)
                        .containsEntry("ruleId", "BR-S-05")
                        .containsEntry("severity", "FATAL"));
    }

    private String withNumber(String number) {
        String request = validRequest.replace(REFERENCE_NUMBER, number);
        assertThat(request).isNotEqualTo(validRequest);
        return request;
    }

    private <T> ResponseEntity<T> submit(UUID company, String body, Class<T> type) {
        return http.post().uri("/api/v1/invoices")
                .header("X-Mystix-Company-Id", company.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_XML, MediaType.APPLICATION_JSON)
                .body(body.getBytes(StandardCharsets.UTF_8))
                .retrieve()
                .toEntity(type);
    }

    private ResponseEntity<byte[]> artifact(UUID company, String id, String kind) {
        return http.get().uri("/api/v1/invoices/{id}/artifacts/{kind}", id, kind)
                .header("X-Mystix-Company-Id", company.toString())
                .retrieve().toEntity(byte[].class);
    }

    @SuppressWarnings("unchecked")
    private static List<String> fields(ResponseEntity<Map> response) {
        return ((List<Map<String, Object>>) response.getBody().get("fieldErrors")).stream()
                .map(e -> (String) e.get("field"))
                .toList();
    }

    private static byte[] resource(String path) throws IOException {
        try (InputStream in = InvoiceApiTests.class.getClassLoader().getResourceAsStream(path)) {
            assertThat(in).as(path).isNotNull();
            return in.readAllBytes();
        }
    }
}
