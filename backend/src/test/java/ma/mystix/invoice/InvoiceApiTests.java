package ma.mystix.invoice;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import ma.mystix.TestcontainersConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClient;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class InvoiceApiTests {

    @Value("${local.server.port}")
    int port;

    RestClient http;
    String validRequest;

    @BeforeEach
    void setUp() throws IOException {
        http = RestClient.builder()
                .baseUrl("http://localhost:" + port)
                .defaultStatusHandler(status -> true, (request, response) -> { })
                .build();
        validRequest = new String(resource("fixtures/api/invoice-basic.request.json"), StandardCharsets.UTF_8);
    }

    @Test
    void returnsUblIdenticalToReferenceFixture() throws IOException {
        ResponseEntity<byte[]> response = http.post().uri("/api/v1/invoices")
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_XML)
                .body(validRequest)
                .retrieve()
                .toEntity(byte[].class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_XML);
        assertThat(response.getHeaders().getFirst("X-Mystix-Canonical-Version")).isEqualTo("1.0");
        assertThat(response.getHeaders().getFirst("X-Mystix-En16931-Artefacts")).isEqualTo("1.3.16");
        assertThat(response.getBody()).isEqualTo(resource("fixtures/ubl/invoice-basic.expected.xml"));
    }

    @Test
    void reportsCanonicalViolationsWithTheirJsonPath() {
        String request = validRequest
                .replace("\"gln\": \"6110000000017\"", "\"gln\": \"6110000000018\"")
                .replace("\"gtin\": \"6110000000109\"", "\"gtin\": \"6110000000100\"");

        ResponseEntity<Map> response = postForError(request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody())
                .containsEntry("errorCode", "INVOICE_REJECTED")
                .containsEntry("stage", "MAPPING");
        assertThat(fields(response)).containsExactlyInAnyOrder("seller", "lines[0]");
    }

    @Test
    void reportsMissingFieldsBeforeMapping() {
        String request = validRequest.replace("\"number\": \"FA-2026-000123\",", "");

        ResponseEntity<Map> response = postForError(request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).containsEntry("errorCode", "VALIDATION_FAILED");
        assertThat(fields(response)).contains("number");
    }

    @Test
    void reportsNestedFieldPaths() {
        String request = validRequest.replace("\"countryCode\": \"MA\" }\n  },\n  \"buyer\"",
                "\"countryCode\": \"Maroc\" }\n  },\n  \"buyer\"");
        assertThat(request).isNotEqualTo(validRequest);

        ResponseEntity<Map> response = postForError(request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(fields(response)).contains("seller.address.countryCode");
    }

    @Test
    void returnsEn16931RuleViolations() {
        // EN 16931 BR-S-05: a standard rated (S) line must have a VAT rate greater than zero.
        String request = validRequest.replace("\"vat\": { \"category\": \"S\", \"ratePercent\": \"10.00\" }",
                "\"vat\": { \"category\": \"S\", \"ratePercent\": 0 }");
        assertThat(request).isNotEqualTo(validRequest);

        ResponseEntity<Map> response = postForError(request);

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

    private ResponseEntity<Map> postForError(String body) {
        return http.post().uri("/api/v1/invoices")
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_XML, MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .toEntity(Map.class);
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
