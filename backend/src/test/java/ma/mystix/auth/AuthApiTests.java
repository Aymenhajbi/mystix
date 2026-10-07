package ma.mystix.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import ma.mystix.TestcontainersConfiguration;
import ma.mystix.tenant.CompanyService;
import ma.mystix.tenant.Ice;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.client.RestClient;

/** Authentication with sessions required (ADR-0013); synthetic accounts, generated in the test. */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"mystix.auth.required=true", "mystix.admin.enabled=true", "mystix.auth.max-failed-attempts=3"})
class AuthApiTests {

    private static final String OPERATOR_PASSWORD = "operateur-" + UUID.randomUUID();
    private static final String USER_PASSWORD = "utilisateur-" + UUID.randomUUID();

    @Value("${local.server.port}")
    int port;

    @Autowired
    CompanyService companies;

    @Autowired
    JdbcClient jdbc;

    RestClient http;

    @BeforeEach
    void setUp() {
        http = RestClient.builder().baseUrl("http://localhost:" + port)
                .defaultStatusHandler(status -> true, (request, response) -> { }).build();
    }

    @Test
    @SuppressWarnings("unchecked")
    void sessionsScopeEveryCallToTheEnvironmentsOfTheUser() {
        UUID a = companies.register(new Ice("000000040000040"), "Auth Environnement A SARL", null).id();
        UUID b = companies.register(new Ice("000000041000041"), "Auth Environnement B SARL", null).id();

        // No session: refused, even with an environment header.
        ResponseEntity<Map> anonymous = get("/api/v1/invoices", null, a);
        assertThat(anonymous.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(anonymous.getBody()).containsEntry("errorCode", "AUTH_REQUIRED");

        // First administrator, then the setup is closed.
        assertThat(http.get().uri("/api/v1/auth/setup").retrieve().body(Map.class)).containsEntry("setupNeeded", true);
        ResponseEntity<Map> weak = post("/api/v1/auth/setup", null,
                Map.of("email", "ops@mystix.test", "displayName", "Opérateur", "password", "court"));
        assertThat(weak.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        ResponseEntity<Map> setup = post("/api/v1/auth/setup", null,
                Map.of("email", "Ops@Mystix.test", "displayName", "Opérateur", "password", OPERATOR_PASSWORD));
        assertThat(setup.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String operator = (String) setup.getBody().get("token");
        assertThat((Map<String, Object>) setup.getBody().get("me")).containsEntry("operator", true)
                .containsEntry("email", "ops@mystix.test");
        assertThat(post("/api/v1/auth/setup", null,
                Map.of("email", "x@mystix.test", "displayName", "X", "password", OPERATOR_PASSWORD)).getBody())
                .containsEntry("errorCode", "AUTH_SETUP_DONE");

        // Passwords are stored as PBKDF2 hashes only.
        String hash = jdbc.sql("SELECT password_hash FROM app_user WHERE email = 'ops@mystix.test'")
                .query(String.class).single();
        assertThat(hash).startsWith("pbkdf2-sha256$600000$").doesNotContain(OPERATOR_PASSWORD);

        // The operator sees every environment and the operator console.
        assertThat(http.get().uri("/api/v1/admin/environments").header("Authorization", "Bearer " + operator)
                .retrieve().toBodilessEntity().getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(((List<Map<String, Object>>) get("/api/v1/auth/me", operator, null).getBody().get("companies")))
                .extracting(c -> c.get("id")).contains(a.toString(), b.toString());

        // A member of A only.
        assertThat(post("/api/v1/auth/users", operator, Map.of("email", "membre@mystix.test", "displayName",
                "Membre A", "password", USER_PASSWORD, "companyId", a.toString(), "role", "MEMBER"))
                .getStatusCode()).isEqualTo(HttpStatus.CREATED);
        ResponseEntity<Map> login = post("/api/v1/auth/login", null,
                Map.of("email", "MEMBRE@mystix.test", "password", USER_PASSWORD));
        assertThat(login.getStatusCode()).isEqualTo(HttpStatus.OK);
        String member = (String) login.getBody().get("token");
        Map<String, Object> me = get("/api/v1/auth/me", member, null).getBody();
        assertThat((Map<String, Object>) me.get("activeCompany")).containsEntry("id", a.toString());
        assertThat(me).containsEntry("operator", false);

        // Calls without a header work in the session environment; another environment is refused.
        assertThat(post("/api/v1/partners", member, Map.of("name", "Partenaire de A", "type", "CUSTOMER"))
                .getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat((List<Map<String, Object>>) http.get().uri("/api/v1/partners")
                .header("Authorization", "Bearer " + operator).header("X-Mystix-Company-Id", b.toString())
                .retrieve().body(List.class)).extracting(p -> p.get("name")).doesNotContain("Partenaire de A");
        assertThat(get("/api/v1/partners", member, b).getBody()).containsEntry("errorCode", "AUTH_FORBIDDEN");
        assertThat(put("/api/v1/auth/session/company", member, Map.of("companyId", b.toString())).getBody())
                .containsEntry("errorCode", "AUTH_FORBIDDEN");
        assertThat(get("/api/v1/admin/environments", member, null).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(post("/api/v1/auth/users", member, Map.of("email", "y@mystix.test", "displayName", "Y",
                "password", USER_PASSWORD, "companyId", a.toString(), "role", "MEMBER")).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);

        // The operator switches environment.
        assertThat((Map<String, Object>) put("/api/v1/auth/session/company", operator,
                Map.of("companyId", b.toString())).getBody().get("activeCompany")).containsEntry("id", b.toString());

        // Logout ends the session; a forged token is refused.
        assertThat(post("/api/v1/auth/logout", member, Map.of()).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(get("/api/v1/auth/me", member, null).getBody()).containsEntry("errorCode", "AUTH_SESSION_INVALID");
        assertThat(get("/api/v1/auth/me", "forged-" + UUID.randomUUID(), null).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void repeatedFailuresLockTheAccountWithoutRevealingWhichAccountsExist() {
        UUID c = companies.register(new Ice("000000042000042"), "Auth Verrou SARL", null).id();
        String operator = operatorToken();
        post("/api/v1/auth/users", operator, Map.of("email", "verrou@mystix.test", "displayName", "Verrou",
                "password", USER_PASSWORD, "companyId", c.toString(), "role", "ADMIN"));

        // Unknown e-mail and wrong password: same status, same code.
        ResponseEntity<Map> unknown = post("/api/v1/auth/login", null,
                Map.of("email", "personne@mystix.test", "password", USER_PASSWORD));
        ResponseEntity<Map> wrong = post("/api/v1/auth/login", null,
                Map.of("email", "verrou@mystix.test", "password", "mauvais-mot-de-passe"));
        assertThat(unknown.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED).isEqualTo(wrong.getStatusCode());
        assertThat(unknown.getBody().get("errorCode")).isEqualTo(wrong.getBody().get("errorCode"))
                .isEqualTo("AUTH_INVALID_CREDENTIALS");

        post("/api/v1/auth/login", null, Map.of("email", "verrou@mystix.test", "password", "mauvais-2"));
        ResponseEntity<Map> third = post("/api/v1/auth/login", null,
                Map.of("email", "verrou@mystix.test", "password", "mauvais-3"));
        assertThat(third.getStatusCode()).isEqualTo(HttpStatus.LOCKED);
        // Locked: even the right password is refused for now.
        assertThat(post("/api/v1/auth/login", null, Map.of("email", "verrou@mystix.test", "password", USER_PASSWORD))
                .getBody()).containsEntry("errorCode", "AUTH_LOCKED");
    }

    /** The operator of this test context: created by the setup if this test runs first, else signed in. */
    private String operatorToken() {
        ResponseEntity<Map> setup = post("/api/v1/auth/setup", null,
                Map.of("email", "ops@mystix.test", "displayName", "Opérateur", "password", OPERATOR_PASSWORD));
        if (setup.getStatusCode() == HttpStatus.CREATED) {
            return (String) setup.getBody().get("token");
        }
        return (String) post("/api/v1/auth/login", null,
                Map.of("email", "ops@mystix.test", "password", OPERATOR_PASSWORD)).getBody().get("token");
    }

    private ResponseEntity<Map> get(String uri, String token, UUID company) {
        var request = http.get().uri(uri);
        if (token != null) {
            request = request.header("Authorization", "Bearer " + token);
        }
        if (company != null) {
            request = request.header("X-Mystix-Company-Id", company.toString());
        }
        return request.retrieve().toEntity(Map.class);
    }

    private ResponseEntity<Map> post(String uri, String token, Map<String, ?> body) {
        var request = http.post().uri(uri).contentType(MediaType.APPLICATION_JSON);
        if (token != null) {
            request = request.header("Authorization", "Bearer " + token);
        }
        return request.body(body).retrieve().toEntity(Map.class);
    }

    private ResponseEntity<Map> put(String uri, String token, Map<String, ?> body) {
        return http.put().uri(uri).contentType(MediaType.APPLICATION_JSON).header("Authorization", "Bearer " + token)
                .body(body).retrieve().toEntity(Map.class);
    }
}
