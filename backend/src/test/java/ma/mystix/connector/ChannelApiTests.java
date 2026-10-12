package ma.mystix.connector;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import ma.mystix.TestcontainersConfiguration;
import ma.mystix.tenant.CompanyService;
import ma.mystix.tenant.Ice;
import org.junit.jupiter.api.BeforeAll;
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

/**
 * Communication channels (ADR-0015) and the Atelys environment of each client (ADR-0014, amended): one channel
 * configured from Mystix is seen and changed from Atelys, settings are checked per protocol, secrets are refused,
 * tenants are isolated.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@SuppressWarnings({"unchecked", "rawtypes"})
class ChannelApiTests {

    @Value("${local.server.port}")
    int port;

    @Autowired
    CompanyService companies;

    RestClient http;
    UUID company;
    UUID other;

    @BeforeAll
    void companies() {
        http = RestClient.builder().baseUrl("http://localhost:" + port)
                .defaultStatusHandler(status -> true, (request, response) -> { }).build();
        company = companies.register(new Ice("000000050000050"), "Transitaire Fictif Test SARL", null).id();
        other = companies.register(new Ice("000000060000060"), "Autre Fictif Test SARL", null).id();
    }

    @Test
    void everyNewClientHasItsAtelysEnvironment() {
        List<Map> units = list(company, "/api/v1/wms/units");
        assertThat(units).extracting(u -> u.get("code")).contains("U", "KG", "M3");
        List<Map> sites = list(company, "/api/v1/wms/sites");
        assertThat(sites).extracting(s -> s.get("code")).containsExactly("PRINCIPAL");
        List<Map> locations = list(company, "/api/v1/wms/locations?siteId=" + sites.getFirst().get("id"));
        assertThat(locations).extracting(l -> l.get("type"))
                .contains("RECEIVING", "STORAGE", "PRODUCTION", "QUARANTINE", "SHIPPING", "TRANSIT");
    }

    @Test
    void sftpChannelGetsItsDefaultsAndIsSharedWithAtelys() {
        ResponseEntity<Map> created = send(company, "POST", "/api/v1/channels", Map.of(
                "name", "SFTP fournisseur bois", "protocol", "SFTP", "direction", "IN",
                "usedByMystix", true, "usedByAtelys", true,
                "settings", Map.of("host", "sftp.example.test", "username", "mystix", "inboundDirectory", "/out")));
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        Map body = created.getBody();
        assertThat(body.get("status")).isEqualTo("DRAFT");
        Map settings = (Map) body.get("settings");
        assertThat(settings.get("port")).isEqualTo(22);
        assertThat(settings.get("authMethod")).isEqualTo("PRIVATE_KEY");
        String id = (String) body.get("id");

        assertThat(list(company, "/api/v1/channels?app=ATELYS")).extracting(c -> c.get("id")).contains(id);
        assertThat(list(company, "/api/v1/channels?app=MYSTIX")).extracting(c -> c.get("id")).contains(id);

        // Active needs the alias of the key in the secret store.
        Map<String, Object> update = new HashMap<>(Map.of("name", "SFTP fournisseur bois", "protocol", "SFTP",
                "direction", "IN", "usedByMystix", false, "usedByAtelys", true, "status", "ACTIVE",
                "settings", settings));
        assertThat(send(company, "PUT", "/api/v1/channels/" + id, update).getBody().get("errorCode"))
                .isEqualTo("CHANNEL_INCOMPLETE");
        update.put("credentialRef", "vault:sftp/fournisseur-bois");
        Map updated = send(company, "PUT", "/api/v1/channels/" + id, update).getBody();
        assertThat(updated.get("status")).isEqualTo("ACTIVE");
        assertThat(list(company, "/api/v1/channels?app=MYSTIX")).extracting(c -> c.get("id")).doesNotContain(id);

        assertThat(send(company, "DELETE", "/api/v1/channels/" + id, null).getStatusCode())
                .isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(send(company, "GET", "/api/v1/channels/" + id, null).getBody().get("errorCode"))
                .isEqualTo("CHANNEL_NOT_FOUND");
    }

    @Test
    void as2SettingsAreCheckedAndSecretsRefused() {
        ResponseEntity<Map> bad = send(company, "POST", "/api/v1/channels", Map.of(
                "name", "AS2 client", "protocol", "AS2", "direction", "OUT",
                "settings", Map.of("localAs2Id", "MYSTIX", "partnerAs2Id", "CLIENT", "mdn", "ASYNC",
                        "password", "should-never-be-stored")));
        assertThat(bad.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(String.valueOf(bad.getBody())).contains("settings.password", "settings.url",
                "settings.asyncMdnUrl");

        ResponseEntity<Map> ok = send(company, "POST", "/api/v1/channels", Map.of(
                "name", "AS2 client", "protocol", "AS2", "direction", "OUT",
                "settings", Map.of("url", "https://as2.example.test/receive", "localAs2Id", "MYSTIX",
                        "partnerAs2Id", "CLIENT 01")));
        assertThat(ok.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        Map settings = (Map) ok.getBody().get("settings");
        assertThat(settings).containsEntry("signing", "SHA-256").containsEntry("encryption", "AES256_CBC")
                .containsEntry("mdn", "SYNC").containsEntry("mdnSigned", true);

        assertThat(send(company, "POST", "/api/v1/channels", Map.of("name", "AS2 client", "protocol", "WEBHOOK",
                "direction", "OUT", "settings", Map.of("url", "https://hook.example.test")))
                .getBody().get("errorCode")).isEqualTo("CHANNEL_NAME_TAKEN");
    }

    @Test
    void tenantsAreIsolated() {
        String id = (String) send(company, "POST", "/api/v1/channels", Map.of(
                "name", "FTP isolé", "protocol", "FTP", "direction", "OUT",
                "settings", Map.of("host", "ftp.example.test", "username", "u"))).getBody().get("id");
        assertThat(list(other, "/api/v1/channels")).extracting(c -> c.get("id")).doesNotContain(id);
        assertThat(send(other, "GET", "/api/v1/channels/" + id, null).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(send(other, "DELETE", "/api/v1/channels/" + id, null).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(send(company, "GET", "/api/v1/channels/" + id, null).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    private List<Map> list(UUID tenant, String uri) {
        return http.get().uri(uri).header("X-Mystix-Company-Id", tenant.toString()).retrieve().body(List.class);
    }

    private ResponseEntity<Map> send(UUID tenant, String method, String uri, Object body) {
        var request = http.method(org.springframework.http.HttpMethod.valueOf(method)).uri(uri)
                .header("X-Mystix-Company-Id", tenant.toString());
        if (body != null) {
            request = request.contentType(MediaType.APPLICATION_JSON).body(body);
        }
        return request.retrieve().toEntity(Map.class);
    }
}
