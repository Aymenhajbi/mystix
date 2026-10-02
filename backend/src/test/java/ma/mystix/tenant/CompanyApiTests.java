package ma.mystix.tenant;

import static org.assertj.core.api.Assertions.assertThat;

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
class CompanyApiTests {

    @Value("${local.server.port}")
    int port;

    RestClient http;

    @BeforeEach
    void setUp() {
        http = RestClient.builder()
                .baseUrl("http://localhost:" + port)
                .defaultStatusHandler(status -> true, (request, response) -> { })
                .build();
    }

    @Test
    void createsThenReadsACompany() {
        ResponseEntity<Map> created = post(Map.of("ice", "000000101000011", "legalName", " Atlas Test SARL ",
                "taxIdentifier", "12345678"));

        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String id = (String) created.getBody().get("id");
        assertThat(created.getHeaders().getLocation()).hasToString("/api/v1/companies/" + id);
        assertThat(created.getBody()).containsEntry("legalName", "Atlas Test SARL");

        ResponseEntity<Map> read = http.get().uri("/api/v1/companies/{id}", id).retrieve().toEntity(Map.class);
        assertThat(read.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(read.getBody())
                .containsEntry("ice", "000000101000011")
                .containsEntry("taxIdentifier", "12345678");
    }

    @Test
    void rejectsDuplicateIceWithStructuredError() {
        Map<String, String> body = Map.of("ice", "000000102000022", "legalName", "Duplicate Test SA");
        assertThat(post(body).getStatusCode()).isEqualTo(HttpStatus.CREATED);

        ResponseEntity<Map> duplicate = post(body);

        assertThat(duplicate.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(duplicate.getBody())
                .containsEntry("errorCode", "COMPANY_ICE_ALREADY_EXISTS")
                .containsEntry("stage", "API")
                .containsEntry("retryable", false)
                .containsKeys("userMessage", "suggestedAction");
        assertThat(asMap(duplicate.getBody().get("userMessage"))).containsKeys("fr", "ar");
    }

    @Test
    void rejectsInvalidIceWithFieldError() {
        ResponseEntity<Map> response = post(Map.of("ice", "12AB", "legalName", "Invalid Test"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).containsEntry("errorCode", "VALIDATION_FAILED");
        assertThat((List<?>) response.getBody().get("fieldErrors"))
                .anySatisfy(e -> assertThat(asMap(e)).containsEntry("field", "ice"));
    }

    @Test
    void unknownCompanyIsNotFound() {
        ResponseEntity<Map> response = http.get()
                .uri("/api/v1/companies/{id}", "00000000-0000-0000-0000-000000000000")
                .retrieve().toEntity(Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).containsEntry("errorCode", "COMPANY_NOT_FOUND");
    }

    @Test
    void unknownRouteReturnsStructuredError() {
        ResponseEntity<Map> response = http.get().uri("/api/v1/nope").retrieve().toEntity(Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).containsEntry("errorCode", "RESOURCE_NOT_FOUND");
    }

    @Test
    void healthIsUp() {
        ResponseEntity<Map> response = http.get().uri("/actuator/health").retrieve().toEntity(Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).containsEntry("status", "UP");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        return (Map<String, Object>) value;
    }

    private ResponseEntity<Map> post(Map<String, String> body) {
        return http.post().uri("/api/v1/companies")
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .toEntity(Map.class);
    }
}
