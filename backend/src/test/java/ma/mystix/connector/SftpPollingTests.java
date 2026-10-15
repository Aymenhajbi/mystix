package ma.mystix.connector;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;

import ma.mystix.TestcontainersConfiguration;
import ma.mystix.flow.FlowService;
import ma.mystix.tenant.CompanyService;
import ma.mystix.tenant.Ice;
import org.apache.sshd.common.config.keys.KeyUtils;
import org.apache.sshd.common.digest.BuiltinDigests;
import org.apache.sshd.common.file.virtualfs.VirtualFileSystemFactory;
import org.apache.sshd.server.SshServer;
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider;
import org.apache.sshd.sftp.server.SftpSubsystemFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;

/**
 * SFTP polling listener (ADR-0016) against a real SSH/SFTP server (Apache MINA SSHD, embedded, on 127.0.0.1): the
 * files of the inbound directory reach the invoice pipeline, are moved or deleted, and never acquired twice; the
 * server key, the credentials and the directory are checked at start with the observed failure named. Secrets are
 * read from a temporary secret directory, as in production from the secret store; nothing is mocked.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "mystix.connectivity.heartbeat=200ms", "mystix.connectivity.reconcile=1h",
        "mystix.connectivity.sftp-stable-after=0s", "mystix.queue.poll-interval=50ms"})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@SuppressWarnings({"unchecked", "rawtypes"})
class SftpPollingTests {

    private static final String REFERENCE_NUMBER = "FA-2026-000123";
    private static final String USER = "mystix";
    private static final String PASSWORD = "s3cret-test-only";
    static final Path SECRETS;

    static {
        try {
            SECRETS = Files.createTempDirectory("mystix-secrets");
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @DynamicPropertySource
    static void secrets(DynamicPropertyRegistry registry) {
        registry.add("mystix.secrets.directory", SECRETS::toString);
    }

    @Value("${local.server.port}")
    int port;

    @Autowired
    CompanyService companies;

    @Autowired
    FlowService flows;

    @Autowired
    JdbcClient jdbc;

    RestClient http;
    UUID company;
    UUID flowId;
    String invoiceJson;
    SshServer sshd;
    Path root;
    String fingerprint;
    KeyPair clientKey;
    int counter;

    @BeforeAll
    void setUp() throws Exception {
        http = RestClient.builder().baseUrl("http://localhost:" + port)
                .defaultStatusHandler(status -> true, (request, response) -> { }).build();
        company = companies.list().stream().filter(c -> c.ice().equals(new Ice("000000001000011"))).findFirst()
                .orElseGet(() -> companies.register(new Ice("000000001000011"), "Fournisseur File Synthetique SARL",
                        null)).id();
        flowId = flows.forSubmission(company, null).id();
        try (InputStream in = getClass().getClassLoader().getResourceAsStream("fixtures/api/invoice-basic.request.json")) {
            invoiceJson = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        clientKey = KeyUtils.generateKeyPair("ssh-rsa", 2048);
        Files.writeString(SECRETS.resolve("sftp-password"), PASSWORD);
        Files.writeString(SECRETS.resolve("sftp-wrong-password"), "not-the-password");
        Files.writeString(SECRETS.resolve("sftp-key"), "-----BEGIN PRIVATE KEY-----\n"
                + Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII))
                .encodeToString(clientKey.getPrivate().getEncoded()) + "\n-----END PRIVATE KEY-----\n");

        root = Files.createTempDirectory("mystix-sftp");
        Files.createDirectories(root.resolve("in"));
        Files.createDirectories(root.resolve("archive"));
        sshd = server(root);
        fingerprint = KeyUtils.getFingerPrint(BuiltinDigests.sha256,
                sshd.getKeyPairProvider().loadKeys(null).iterator().next().getPublic());
    }

    @AfterAll
    void tearDown() throws IOException {
        sshd.stop(true);
    }

    @Test
    void filesReachThePipelineAreArchivedAndNeverTakenTwice() throws Exception {
        String id = listener("SFTP mot de passe", channel("SFTP fournisseur", "sftp-password", "PASSWORD", fingerprint,
                "/in", "MOVE"), "SFTP-PWD");
        start(id);

        String number = number();
        Files.writeString(root.resolve("in/facture-1.json"), invoice(number));
        Files.writeString(root.resolve("in/facture-2.json.part"), invoice(number()));
        Files.writeString(root.resolve("in/notes.txt"), "not an invoice");
        Files.writeString(root.resolve("in/mauvaise.json"), "{\"number\":\"X\"}");

        Map result = poll(id);
        assertThat(result).containsEntry("accepted", 1).containsEntry("rejected", 1);
        assertThat(root.resolve("archive/facture-1.json")).exists();
        assertThat(root.resolve("in/facture-1.json")).doesNotExist();
        // Rejected by the pipeline: left in place for someone to look at; partial and unmatched files untouched.
        assertThat(root.resolve("in/mauvaise.json")).exists();
        assertThat(root.resolve("in/facture-2.json.part")).exists();
        assertThat(root.resolve("in/notes.txt")).exists();

        Map received = events(id).stream().filter(e -> "MESSAGE_RECEIVED".equals(e.get("eventType"))).findFirst()
                .orElseThrow();
        String invoiceId = (String) ((Map) received.get("details")).get("invoiceId");
        assertThat((Map) received.get("details")).containsEntry("file", "/in/facture-1.json").containsEntry("number", number);
        waitFor("/api/v1/invoices/" + invoiceId, i -> "CLEARED".equals(i.get("status")));

        // The same content again, under another name: archived, not handed to the pipeline a second time.
        Files.copy(root.resolve("archive/facture-1.json"), root.resolve("in/facture-1-bis.json"));
        Map again = poll(id);
        assertThat(again).containsEntry("accepted", 0).containsEntry("duplicates", 1);
        assertThat(root.resolve("in/facture-1-bis.json")).doesNotExist();
        // The rejected file is not journaled again at each poll.
        assertThat(events(id)).filteredOn(e -> "MESSAGE_REJECTED".equals(e.get("eventType"))).hasSize(1);
        assertThat(get("/api/v1/connectivity/listeners/" + id)).containsEntry("receivedCount", 1)
                .containsEntry("rejectedCount", 1);
        stop(id);
    }

    @Test
    void aPrivateKeyAuthenticatesAndDeleteRemovesTheFile() throws Exception {
        Files.createDirectories(root.resolve("in-key"));
        String id = listener("SFTP clé", channel("SFTP clé privée", "sftp-key", "PRIVATE_KEY", fingerprint,
                "/in-key", "DELETE"), "SFTP-KEY");
        start(id);
        Files.writeString(root.resolve("in-key/f.json"), invoice(number()));
        assertThat(poll(id)).containsEntry("accepted", 1);
        assertThat(root.resolve("in-key/f.json")).doesNotExist();
        stop(id);
    }

    @Test
    void aStartNamesWhatFailed() {
        // The server presents another key: never accepted, whatever the credentials.
        String wrongKey = listener("SFTP mauvaise clé", channel("SFTP clé serveur fausse", "sftp-password",
                "PASSWORD", "SHA256:AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA", "/in", "KEEP"), "SFTP-HK");
        assertStartFails(wrongKey, "HOST_KEY_MISMATCH");

        String wrongPassword = listener("SFTP mauvais mot de passe", channel("SFTP identifiants faux",
                "sftp-wrong-password", "PASSWORD", fingerprint, "/in", "KEEP"), "SFTP-AUTH");
        assertStartFails(wrongPassword, "AUTH_FAILED");

        String noDirectory = listener("SFTP répertoire absent", channel("SFTP répertoire inconnu", "sftp-password",
                "PASSWORD", fingerprint, "/nowhere", "KEEP"), "SFTP-DIR");
        assertStartFails(noDirectory, "REMOTE_DIRECTORY_UNAVAILABLE");

        // A secret the store does not have: refused before any connection.
        String noSecret = listener("SFTP sans secret", channel("SFTP secret absent", "sftp-absent", "PASSWORD",
                fingerprint, "/in", "KEEP"), "SFTP-SEC");
        ResponseEntity<Map> refused = send(HttpMethod.POST, "/api/v1/connectivity/listeners/" + noSecret + "/start", null);
        assertThat(refused.getBody()).containsEntry("errorCode", "LISTENER_NOT_READY");
        assertThat((List<Map>) refused.getBody().get("fieldErrors")).extracting(f -> f.get("field")).contains("secret");
    }

    @Test
    void aListenerRunningOnAnotherInstanceIsNotStartedTwice() {
        String id = listener("SFTP ailleurs", channel("SFTP autre nœud", "sftp-password", "PASSWORD", fingerprint,
                "/in", "KEEP"), "SFTP-ELSE");
        jdbc.sql("""
                        UPDATE listener SET desired_state = 'RUNNING', runtime_state = 'RUNNING',
                               runtime_instance = 'other-node', last_heartbeat_at = now() WHERE id = :id
                        """).param("id", UUID.fromString(id)).update();
        ResponseEntity<Map> r = send(HttpMethod.POST, "/api/v1/connectivity/listeners/" + id + "/start", null);
        assertThat(r.getBody()).containsEntry("errorCode", "LISTENER_START_FAILED");
        assertThat(events(id)).anySatisfy(e -> {
            assertThat(e.get("eventType")).isEqualTo("START_REFUSED");
            assertThat((Map) e.get("details")).containsEntry("code", "RUNNING_ELSEWHERE");
        });
    }

    @Test
    void aServerThatGoesAwayShowsInTheHeartbeat() throws Exception {
        Path otherRoot = Files.createTempDirectory("mystix-sftp-2");
        Files.createDirectories(otherRoot.resolve("in"));
        SshServer other = server(otherRoot);
        String otherFingerprint = KeyUtils.getFingerPrint(BuiltinDigests.sha256,
                other.getKeyPairProvider().loadKeys(null).iterator().next().getPublic());
        String id = listener("SFTP éphémère", channel("SFTP serveur qui s'arrête", "sftp-password", "PASSWORD",
                otherFingerprint, "/in", "KEEP", other.getPort()), "SFTP-GONE");
        start(id);
        other.stop(true);

        ResponseEntity<Map> failed = send(HttpMethod.POST, "/api/v1/connectivity/listeners/" + id + "/poll", null);
        assertThat(failed.getBody()).containsEntry("errorCode", "LISTENER_POLL_FAILED");
        Map degraded = waitFor("/api/v1/connectivity/listeners/" + id, l -> "DEGRADED".equals(l.get("state")));
        assertThat((Map) degraded.get("lastError")).containsEntry("code", "HOST_UNREACHABLE");
        stop(id);
    }

    // ---------- helpers ----------

    private SshServer server(Path directory) throws IOException {
        SshServer server = SshServer.setUpDefaultServer();
        server.setHost("127.0.0.1");
        server.setPort(0);
        server.setKeyPairProvider(new SimpleGeneratorHostKeyProvider(Files.createTempFile("hostkey", ".ser")));
        server.setPasswordAuthenticator((user, password, session) -> USER.equals(user) && PASSWORD.equals(password));
        server.setPublickeyAuthenticator((user, key, session) -> USER.equals(user)
                && KeyUtils.compareKeys(key, clientKey.getPublic()));
        server.setSubsystemFactories(List.of(new SftpSubsystemFactory()));
        server.setFileSystemFactory(new VirtualFileSystemFactory(directory));
        server.start();
        return server;
    }

    private void assertStartFails(String id, String code) {
        ResponseEntity<Map> r = send(HttpMethod.POST, "/api/v1/connectivity/listeners/" + id + "/start", null);
        assertThat(r.getStatusCode()).as(String.valueOf(r.getBody())).isEqualTo(HttpStatus.BAD_GATEWAY);
        assertThat(r.getBody()).containsEntry("errorCode", "LISTENER_START_FAILED");
        Map listener = get("/api/v1/connectivity/listeners/" + id);
        assertThat(listener).containsEntry("state", "ERROR");
        assertThat((Map) listener.get("lastError")).containsEntry("code", code);
        assertThat(events(id)).extracting(e -> e.get("eventType")).contains("START_FAILED");
    }

    private UUID channel(String name, String secret, String auth, String fp, String dir, String after) {
        return channel(name, secret, auth, fp, dir, after, sshd.getPort());
    }

    private UUID channel(String name, String secret, String auth, String fp, String dir, String after, int sftpPort) {
        Map<String, Object> settings = new HashMap<>(Map.of("host", "127.0.0.1", "port", sftpPort, "username", USER,
                "authMethod", auth, "hostKeyFingerprint", fp, "inboundDirectory", dir, "filePattern", "*.json",
                "afterDownload", after));
        if ("MOVE".equals(after)) {
            settings.put("archiveDirectory", "/archive");
        }
        ResponseEntity<Map> r = send(HttpMethod.POST, "/api/v1/channels", Map.of("name", name, "protocol", "SFTP",
                "direction", "IN", "status", "ACTIVE", "usedByMystix", true, "credentialRef", secret,
                "settings", settings));
        assertThat(r.getStatusCode()).as(String.valueOf(r.getBody())).isEqualTo(HttpStatus.CREATED);
        return UUID.fromString((String) r.getBody().get("id"));
    }

    private String listener(String name, UUID channel, String code) {
        ResponseEntity<Map> entry = send(HttpMethod.POST, "/api/v1/connectivity/entry-points", Map.of("code", code,
                "name", "Factures " + code, "flowId", flowId, "documentType", "INVOICE", "inputFormat", "JSON"));
        assertThat(entry.getStatusCode()).as(String.valueOf(entry.getBody())).isEqualTo(HttpStatus.CREATED);
        ResponseEntity<Map> r = send(HttpMethod.POST, "/api/v1/connectivity/listeners", Map.of("name", name,
                "kind", "SFTP_POLLING", "channelId", channel, "entryPointId", entry.getBody().get("id")));
        assertThat(r.getStatusCode()).as(String.valueOf(r.getBody())).isEqualTo(HttpStatus.CREATED);
        // A poller authenticates itself to the server: no API key of its own.
        assertThat(r.getBody().get("apiKey")).isNull();
        return (String) r.getBody().get("id");
    }

    private void start(String id) {
        ResponseEntity<Map> r = send(HttpMethod.POST, "/api/v1/connectivity/listeners/" + id + "/start", null);
        assertThat(r.getStatusCode()).as(String.valueOf(r.getBody())).isEqualTo(HttpStatus.OK);
        assertThat(r.getBody()).containsEntry("state", "RUNNING");
    }

    private void stop(String id) {
        send(HttpMethod.POST, "/api/v1/connectivity/listeners/" + id + "/stop", null);
    }

    private Map poll(String id) {
        ResponseEntity<Map> r = send(HttpMethod.POST, "/api/v1/connectivity/listeners/" + id + "/poll", null);
        assertThat(r.getStatusCode()).as(String.valueOf(r.getBody())).isEqualTo(HttpStatus.OK);
        return r.getBody();
    }

    private synchronized String number() {
        return "FA-SFTP-" + System.nanoTime() % 1_000_000_000 + "-" + (++counter);
    }

    private String invoice(String number) {
        LocalDate today = LocalDate.now(ZoneId.of("Africa/Casablanca"));
        return invoiceJson.replace(REFERENCE_NUMBER, number)
                .replaceAll("\"issueDate\"\\s*:\\s*\"[0-9-]+\"", "\"issueDate\": \"" + today + "\"")
                .replaceAll("\"dueDate\"\\s*:\\s*\"[0-9-]+\"", "\"dueDate\": \"" + today.plusDays(30) + "\"");
    }

    private ResponseEntity<Map> send(HttpMethod method, String uri, Object body) {
        var request = http.method(method).uri(uri).header("X-Mystix-Company-Id", company.toString());
        if (body != null) {
            request = request.contentType(MediaType.APPLICATION_JSON).body(body);
        }
        return request.retrieve().toEntity(Map.class);
    }

    private Map get(String uri) {
        return http.get().uri(uri).header("X-Mystix-Company-Id", company.toString()).retrieve().body(Map.class);
    }

    private List<Map> events(String id) {
        return http.get().uri("/api/v1/connectivity/listeners/" + id + "/events")
                .header("X-Mystix-Company-Id", company.toString()).retrieve().body(List.class);
    }

    private Map waitFor(String uri, Predicate<Map> done) {
        Map last = null;
        for (int i = 0; i < 100; i++) {
            last = get(uri);
            if (done.test(last)) {
                return last;
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        throw new AssertionError(uri + " did not reach the expected state: " + last);
    }
}
