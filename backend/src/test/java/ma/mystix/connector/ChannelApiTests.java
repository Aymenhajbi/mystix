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

    /**
     * Public certificate only, self-signed for these tests ("Mystix Test partner (fictif)"); its private key was
     * never kept. Inline because *.pem files are ignored by git, to keep keys out of the repository.
     */
    static final String PARTNER_CERTIFICATE = """
-----BEGIN CERTIFICATE-----
MIIDMzCCAhugAwIBAgIIOEkhU0N+OiQwDQYJKoZIhvcNAQEMBQAwSDELMAkGA1UE
BhMCTUExEjAQBgNVBAoTCVN5bnRoZXRpYzElMCMGA1UEAxMcTXlzdGl4IFRlc3Qg
cGFydG5lciAoZmljdGlmKTAeFw0yNjEwMTIyMDM2MDlaFw0zNjEwMDkyMDM2MDla
MEgxCzAJBgNVBAYTAk1BMRIwEAYDVQQKEwlTeW50aGV0aWMxJTAjBgNVBAMTHE15
c3RpeCBUZXN0IHBhcnRuZXIgKGZpY3RpZikwggEiMA0GCSqGSIb3DQEBAQUAA4IB
DwAwggEKAoIBAQCLXq1cep4osy8QlR+3SwsCrvibWDklQHtM5iSIa+wevCMDeU/u
M7yEr2yPP3fIdkJaeDftNQ6cscY7NCn209XmdsEqJ/s42X2WD0tmPjSDeylVCAP4
2gHWZP+FVsZ2Wf9bWsQTrr/1tNWvuL02vEpeQIAtiCJ3NS+9hQPZLK8nzlEZG22V
8k31vEvA2JUyxOfoxj6wP+clKbW9VwyQvlkNZ+TT/WZN4qj4dLRyfSeuiLmY9jpJ
lbQ57biEbLaMT2Z9U/xw45tF/3sIkIBm8okBeD9XaQY14F4XXfFbWLtkVnKIFCLi
b66SAPciGcQweasfxufutjecGLe3iiijPiOHAgMBAAGjITAfMB0GA1UdDgQWBBSM
Ds2ZEzyZb5EEXrEkc6WaTJx2djANBgkqhkiG9w0BAQwFAAOCAQEATBfK7yrO4r/8
qVkAAh6PNW3aBQAO7UghteoaDelPaBRu7mrxkhnFqf5Ij21w1bLM49aA3VqUxHRF
xJ5b6s5ZPrunJNVz/SfKLY1qNEllXWBRPfARua83NqtRigEfPEt6x7pFyMhBThq6
hltg/muNWJEepnSzJ0lfFDxG755uN2gA9C5SEzUVwSktQSRUdHvB05FJOoTS5cH/
45v7wxfUjdhrs/cabmUzBUBOCx+Ea6IFaktAPpL5dCLi6shcnYFESIaJY7Hb8ITn
ynoymbPOvfWU+xuzRi/PqnAbpPzO6pWWa55J9Gafr2NH4DaeS00+BC/thRtL/53m
ZZWhQgmhmg==
-----END CERTIFICATE-----
            """;

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

    @Test
    void publicCertificatesAreReadAndPrivateKeysRefused() {
        String pem = PARTNER_CERTIFICATE;
        String id = (String) send(company, "POST", "/api/v1/channels", Map.of(
                "name", "AS2 certificats", "protocol", "AS2", "direction", "IN",
                "settings", Map.of("localAs2Id", "MYSTIX", "partnerAs2Id", "PARTENAIRE"))).getBody().get("id");
        String base = "/api/v1/channels/" + id + "/certificates";

        ResponseEntity<Map> added = send(company, "POST", base, Map.of("role", "PARTNER", "certificate", pem));
        assertThat(added.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat((String) added.getBody().get("subject")).contains("Mystix Test partner (fictif)");
        assertThat((String) added.getBody().get("sha256Fingerprint")).matches("^([0-9A-F]{2}:){31}[0-9A-F]{2}$");
        assertThat(added.getBody()).containsEntry("keyAlgorithm", "RSA").containsEntry("keySize", 2048);
        assertThat(send(company, "POST", base, Map.of("role", "PARTNER", "certificate", pem)).getBody()
                .get("errorCode")).isEqualTo("CERTIFICATE_DUPLICATE");

        // A private key never gets in, even next to a valid certificate.
        String withKey = pem + "-----BEGIN PRIVATE KEY-----\nMIIBVQ==\n-----END PRIVATE KEY-----\n";
        assertThat(send(company, "POST", base, Map.of("role", "OWN", "certificate", withKey)).getBody()
                .get("errorCode")).isEqualTo("CERTIFICATE_PRIVATE_KEY_REFUSED");
        assertThat(send(company, "POST", base, Map.of("role", "OWN", "certificate", "not a certificate")).getBody()
                .get("errorCode")).isEqualTo("CERTIFICATE_INVALID");

        assertThat(list(company, base)).hasSize(1);
        assertThat(list(company, "/api/v1/channels/certificates")).extracting(c -> c.get("channelId")).contains(id);
        assertThat(list(other, "/api/v1/channels/certificates")).extracting(c -> c.get("channelId"))
                .doesNotContain(id);
        assertThat(send(other, "GET", base, null).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

        // Deleting the channel removes its certificates.
        send(company, "DELETE", "/api/v1/channels/" + id, null);
        assertThat(list(company, "/api/v1/channels/certificates")).extracting(c -> c.get("channelId"))
                .doesNotContain(id);
    }

    @Test
    void catalogGroupsTheParameters() {
        Map catalog = http.get().uri("/api/v1/channels/catalog").retrieve().body(Map.class);
        List<Map> protocols = (List<Map>) catalog.get("protocols");
        Map sftp = protocols.stream().filter(p -> "SFTP".equals(p.get("code"))).findFirst().orElseThrow();
        Map<String, String> groups = new java.util.HashMap<>();
        ((List<Map>) sftp.get("fields")).forEach(f -> groups.put((String) f.get("key"), (String) f.get("group")));
        assertThat(groups).containsEntry("host", "CONNECTION").containsEntry("inboundDirectory", "FILES")
                .containsEntry("hostKeyFingerprint", "SECURITY").containsEntry("archiveDirectory", "ADVANCED");
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
