package ma.mystix.device;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import ma.mystix.TestcontainersConfiguration;
import ma.mystix.auth.AuthService;
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
 * Atelys Device Hub API (ADR-0019, lot D) with authentication on: terminals check in as members, administrators
 * steer them from the portal, another environment sees nothing. Terminals here are simulated by HTTP calls.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "mystix.auth.required=true")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@SuppressWarnings({"unchecked", "rawtypes"})
class DeviceApiTests {

    @Value("${local.server.port}")
    int port;

    @Autowired
    CompanyService companies;

    @Autowired
    AuthService auth;

    RestClient http;
    UUID company;
    UUID other;
    String member;
    String admin;
    String outsider;

    @BeforeAll
    void users() {
        http = RestClient.builder().baseUrl("http://localhost:" + port)
                .defaultStatusHandler(status -> true, (request, response) -> { }).build();
        company = companies.register(new Ice("000000050000050"), "Terminaux Fictifs SARL", null).id();
        other = companies.register(new Ice("000000051000051"), "Autre Entrepot Fictif SARL", null).id();
        member = user(company, "MEMBER");
        admin = user(company, "ADMIN");
        outsider = user(other, "ADMIN");
    }

    @Test
    void aTerminalRegistersOnceAndIsSteeredByAnAdministrator() {
        String installation = UUID.randomUUID().toString();
        Map first = post("/api/v1/atelys/devices/check-in", member, checkIn(installation, null)).getBody();
        UUID device = UUID.fromString((String) first.get("deviceId"));
        assertThat(first).containsEntry("resetRequested", false).containsEntry("testRequested", false);
        // Same installation: same device, refreshed.
        Map again = post("/api/v1/atelys/devices/check-in", member, checkIn(installation, null)).getBody();
        assertThat(again).containsEntry("deviceId", device.toString());
        Map view = get("/api/v1/atelys/devices/" + device, member).getBody();
        assertThat((Map) view.get("device")).containsEntry("health", "ACTION_REQUIRED")
                .containsEntry("model", "CK67").containsEntry("lastSeenBy", email(member));

        // A member reads but does not steer.
        assertThat(put("/api/v1/atelys/devices/" + device + "/preference", member,
                Map.of("adapterId", "keyboard-wedge")).getBody()).containsEntry("errorCode", "AUTH_FORBIDDEN");
        assertThat(put("/api/v1/atelys/devices/" + device + "/preference", admin,
                Map.of("adapterId", "camera")).getBody()).containsEntry("errorCode", "DEVICE_ADAPTER_UNKNOWN");
        assertThat(put("/api/v1/atelys/devices/" + device + "/preference", admin,
                Map.of("adapterId", "honeywell-data-intent")).getBody())
                .containsEntry("preferredAdapter", "honeywell-data-intent").containsEntry("preferredBy", email(admin));
        assertThat(put("/api/v1/atelys/devices/" + device + "/label", admin, Map.of("label", "Quai 1")).getBody())
                .containsEntry("label", "Quai 1");

        // A scan test is asked; the terminal sees it, sends the result, the request is cleared.
        post("/api/v1/atelys/devices/" + device + "/test-request", admin, null);
        assertThat(post("/api/v1/atelys/devices/check-in", member, checkIn(installation, null)).getBody())
                .containsEntry("testRequested", true).containsEntry("preferredAdapter", "honeywell-data-intent");
        Map<String, Object> failed = diagnostic("TIMEOUT", false);
        assertThat(post("/api/v1/atelys/devices/" + device + "/diagnostics", member, failed).getStatusCode())
                .isEqualTo(HttpStatus.CREATED);
        // The terminal resends the same result: stored once.
        post("/api/v1/atelys/devices/" + device + "/diagnostics", member, failed);
        view = get("/api/v1/atelys/devices/" + device, member).getBody();
        assertThat((List) view.get("diagnostics")).hasSize(1);
        assertThat((Map) view.get("device")).containsEntry("health", "ERROR").containsEntry("lastErrorCode", "TIMEOUT")
                .containsEntry("testRequestedAt", null);

        // A physical pass, then the terminal reports its validated configuration: healthy.
        post("/api/v1/atelys/devices/" + device + "/diagnostics", member, diagnostic("PASSED", false));
        post("/api/v1/atelys/devices/check-in", member, checkIn(installation, "honeywell-data-intent"));
        assertThat((Map) get("/api/v1/atelys/devices/" + device, member).getBody().get("device"))
                .containsEntry("health", "OK").containsEntry("activeAdapter", "honeywell-data-intent");

        // Reset: asked from the portal, done when the terminal reports no configuration.
        var validatedBefore = java.time.OffsetDateTime.now().minusMinutes(1);
        post("/api/v1/atelys/devices/" + device + "/reset", admin, null);
        assertThat(post("/api/v1/atelys/devices/check-in", member,
                checkIn(installation, "honeywell-data-intent", validatedBefore)).getBody()).containsEntry("resetRequested", true);
        assertThat(post("/api/v1/atelys/devices/check-in", member, checkIn(installation, null)).getBody())
                .containsEntry("resetRequested", false);

        // Every portal change is in the audit trail.
        assertThat((List<Map>) get("/api/v1/atelys/devices/" + device, admin).getBody().get("audit"))
                .extracting(e -> e.get("action"))
                .contains("DEVICE_REGISTERED", "DEVICE_PREFERENCE", "DEVICE_RENAMED", "DEVICE_TEST_REQUESTED",
                        "DEVICE_DIAGNOSTIC", "DEVICE_RESET_REQUESTED");

        // Another environment sees nothing.
        assertThat(get("/api/v1/atelys/devices/" + device, outsider).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat((List) list("/api/v1/atelys/devices", outsider)).isEmpty();
    }

    @Test
    void profilesAreVersionedSentToTerminalsAndRetired() {
        Map<String, Object> body = new HashMap<>(Map.of("name", "CK67 Data Intent", "scope", "MODEL",
                "manufacturer", "Honeywell", "models", List.of("CK67"), "recommendedAdapter", "honeywell-data-intent",
                "confidence", "MEDIUM", "status", "EXPERIMENTAL", "minSdk", 34));
        assertThat(post("/api/v1/atelys/device-profiles", member, body).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        Map created = post("/api/v1/atelys/device-profiles", admin, body).getBody();
        assertThat(created).containsEntry("version", 1).containsEntry("status", "EXPERIMENTAL");
        String id = (String) created.get("id");

        // A Honeywell-only adapter on another manufacturer is refused.
        Map<String, Object> wrong = new HashMap<>(body);
        wrong.put("manufacturer", "Acme");
        assertThat(post("/api/v1/atelys/device-profiles", admin, wrong).getBody())
                .containsEntry("errorCode", "DEVICE_ADAPTER_UNKNOWN");

        body.put("status", "VALIDATED");
        assertThat(put("/api/v1/atelys/device-profiles/" + id, admin, body).getBody())
                .containsEntry("version", 2).containsEntry("status", "VALIDATED");
        Map seen = post("/api/v1/atelys/devices/check-in", member, checkIn(UUID.randomUUID().toString(), null)).getBody();
        assertThat((List<Map>) seen.get("profiles")).extracting(p -> p.get("id")).contains(id);

        assertThat(post("/api/v1/atelys/device-profiles/" + id + "/retire", admin, null).getBody())
                .containsEntry("status", "RETIRED").containsEntry("version", 3);
        seen = post("/api/v1/atelys/devices/check-in", member, checkIn(UUID.randomUUID().toString(), null)).getBody();
        assertThat((List<Map>) seen.get("profiles")).extracting(p -> p.get("id")).doesNotContain(id);
        assertThat(put("/api/v1/atelys/device-profiles/" + id, admin, body).getBody())
                .containsEntry("errorCode", "STATUS_TRANSITION_INVALID");
        assertThat((List) list("/api/v1/atelys/device-profiles", outsider)).isEmpty();
    }

    @Test
    void invalidCheckInsAreRefused() {
        assertThat(post("/api/v1/atelys/devices/check-in", member, checkIn("short", null)).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(http.post().uri("/api/v1/atelys/devices/check-in").contentType(MediaType.APPLICATION_JSON)
                .body(checkIn(UUID.randomUUID().toString(), null)).retrieve().toBodilessEntity().getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    // ---------- helpers ----------

    private static Map<String, Object> checkIn(String installation, String active) {
        return checkIn(installation, active, java.time.OffsetDateTime.now());
    }

    private static Map<String, Object> checkIn(String installation, String active, java.time.OffsetDateTime validatedAt) {
        Map<String, Object> m = new HashMap<>(Map.of("installationId", installation, "manufacturer", "Honeywell",
                "brand", "Honeywell", "model", "CK67", "product", "CK67", "androidRelease", "14", "sdkInt", 34,
                "buildId", "TEST-BUILD", "appVersion", "0.1.0", "capabilities", List.of("input.key-events")));
        m.put("resolutionState", active == null ? "CONFIGURATION_REQUIRED" : "AUTO_SELECTED");
        m.put("recommendedAdapter", "honeywell-data-intent");
        m.put("resolutionReasons", List.of("CONFIGURATION_NOT_DETECTABLE"));
        if (active != null) {
            m.put("activeAdapter", active);
            m.put("validatedAt", validatedAt.toString());
        }
        return m;
    }

    private static Map<String, Object> diagnostic(String outcome, boolean simulated) {
        var now = java.time.OffsetDateTime.now();
        return Map.of("id", UUID.randomUUID().toString(), "adapterId", "honeywell-data-intent", "outcome", outcome,
                "simulated", simulated, "startedAt", now.minusSeconds(5).toString(), "finishedAt", now.toString());
    }

    private final Map<String, String> emails = new HashMap<>();

    private String user(UUID companyId, String role) {
        String email = role.toLowerCase() + "-" + UUID.randomUUID() + "@mystix.test";
        char[] password = ("mot-de-passe-" + UUID.randomUUID()).toCharArray();
        auth.createUser(email, "Test " + role, password.clone(), companyId, role);
        String token = auth.login(email, password, "test").token();
        emails.put(token, email);
        return token;
    }

    private String email(String token) {
        return emails.get(token);
    }

    private ResponseEntity<Map> post(String uri, String token, Object body) {
        var request = http.post().uri(uri).header("Authorization", "Bearer " + token);
        if (body != null) {
            request = request.contentType(MediaType.APPLICATION_JSON).body(body);
        }
        return request.retrieve().toEntity(Map.class);
    }

    private ResponseEntity<Map> put(String uri, String token, Object body) {
        return http.put().uri(uri).header("Authorization", "Bearer " + token).contentType(MediaType.APPLICATION_JSON)
                .body(body).retrieve().toEntity(Map.class);
    }

    private List list(String uri, String token) {
        return http.get().uri(uri).header("Authorization", "Bearer " + token).retrieve().body(List.class);
    }

    private ResponseEntity<Map> get(String uri, String token) {
        return http.get().uri(uri).header("Authorization", "Bearer " + token).retrieve().toEntity(Map.class);
    }
}
