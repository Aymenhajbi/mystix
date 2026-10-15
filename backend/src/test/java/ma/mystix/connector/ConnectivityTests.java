package ma.mystix.connector;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Predicate;

import ma.mystix.TestcontainersConfiguration;
import ma.mystix.flow.FlowService;
import ma.mystix.tenant.CompanyService;
import ma.mystix.tenant.Ice;
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
import org.springframework.web.client.RestClient;

/**
 * Connectivity Manager (ADR-0016) against a real server and database: an HTTP inbound listener receives an invoice
 * and the pipeline takes it to (simulated) clearance; refusals at reception are told apart from business refusals;
 * start, stop, preconditions, concurrent starts, restart of the process, heartbeat and tenant isolation.
 * Nothing here is mocked: requests go over HTTP to the listener route served by the application.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "mystix.connectivity.heartbeat=200ms", "mystix.connectivity.reconcile=1h",
        "mystix.queue.poll-interval=50ms"})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@SuppressWarnings({"unchecked", "rawtypes"})
class ConnectivityTests {

    private static final String REFERENCE_NUMBER = "FA-2026-000123";

    @Value("${local.server.port}")
    int port;

    @Autowired
    CompanyService companies;

    @Autowired
    FlowService flows;

    @Autowired
    ListenerSupervisor supervisor;

    @Autowired
    HttpInboundAdapter adapter;

    RestClient http;
    UUID company;
    UUID other;
    UUID flowId;
    String invoiceJson;
    int counter;

    @BeforeAll
    void setUp() throws IOException {
        http = RestClient.builder().baseUrl("http://localhost:" + port)
                .defaultStatusHandler(status -> true, (request, response) -> { }).build();
        // Seller ICE of the reference fixture; the database may be shared with other invoice tests.
        company = companies.list().stream().filter(c -> c.ice().equals(new Ice("000000001000011"))).findFirst()
                .orElseGet(() -> companies.register(new Ice("000000001000011"), "Fournisseur File Synthetique SARL",
                        null)).id();
        other = companies.register(new Ice("000000093000093"), "Autre Connectivite Fictif SARL", null).id();
        flowId = flows.forSubmission(company, null).id();
        try (InputStream in = getClass().getClassLoader().getResourceAsStream("fixtures/api/invoice-basic.request.json")) {
            invoiceJson = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void anInvoiceSentToARunningListenerReachesClearance() {
        Map listener = createListener("HTTP ERP", activeChannel("HTTPS ERP entrant"), entryPoint("ERP-JSON", "JSON"));
        String id = (String) listener.get("id");
        String key = (String) listener.get("apiKey");
        assertThat(key).startsWith("mxk_");
        assertThat(listener).containsEntry("state", "CONFIGURED").containsEntry("desiredState", "STOPPED")
                .containsEntry("endpoint", "/inbound/http/" + id);
        assertThat((String) listener.get("apiKeyHint")).isEqualTo(key.substring(key.length() - 4));
        // The key is shown once: never in a read.
        assertThat(get("/api/v1/connectivity/listeners/" + id).get("apiKey")).isNull();

        // Saved is not started: the route answers 503 until an administrator starts it.
        assertThat(inbound(id, key, MediaType.APPLICATION_JSON, invoice()).getStatusCode())
                .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);

        ResponseEntity<Map> started = send(company, HttpMethod.POST, "/api/v1/connectivity/listeners/" + id + "/start", null);
        assertThat(started.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(started.getBody()).containsEntry("state", "RUNNING").containsEntry("desiredState", "RUNNING");
        assertThat(started.getBody().get("lastHeartbeatAt")).isNotNull();

        // Reception refusals.
        assertThat(inbound(id, null, MediaType.APPLICATION_JSON, invoice()).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(inbound(id, key + "x", MediaType.APPLICATION_JSON, invoice()).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(inbound(id, key, MediaType.TEXT_PLAIN, invoice()).getStatusCode())
                .isEqualTo(HttpStatus.UNSUPPORTED_MEDIA_TYPE);

        // A business refusal: the pipeline said no.
        ResponseEntity<Map> rejected = inbound(id, key, MediaType.APPLICATION_JSON, "{\"number\":\"X\"}");
        assertThat(rejected.getStatusCode().is4xxClientError()).isTrue();
        assertThat(rejected.getHeaders().getFirst("X-Mystix-Correlation-Id")).isNotNull();

        // The real thing.
        ResponseEntity<Map> accepted = inbound(id, key, MediaType.APPLICATION_JSON, invoice());
        assertThat(accepted.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        String correlation = accepted.getHeaders().getFirst("X-Mystix-Correlation-Id");
        assertThat(accepted.getBody()).containsEntry("correlationId", correlation).containsEntry("replayed", false);
        String invoiceId = (String) accepted.getBody().get("invoiceId");
        Map invoice = waitFor("/api/v1/invoices/" + invoiceId, i -> "CLEARED".equals(i.get("status")));
        assertThat(((Map) invoice.get("clearance")).get("simulated")).isEqualTo(true);

        Map after = get("/api/v1/connectivity/listeners/" + id);
        assertThat(after).containsEntry("receivedCount", 1).containsEntry("rejectedCount", 4);
        assertThat(after.get("lastActivityAt")).isNotNull();
        List<Map> events = events(id);
        assertThat(events).extracting(e -> e.get("eventType"))
                .contains("CREATED", "START_REQUESTED", "STARTED", "MESSAGE_REFUSED", "MESSAGE_REJECTED",
                        "MESSAGE_RECEIVED");
        Map received = events.stream().filter(e -> "MESSAGE_RECEIVED".equals(e.get("eventType"))).findFirst().orElseThrow();
        assertThat(received).containsEntry("correlationId", correlation);
        assertThat((Map) received.get("details")).containsEntry("invoiceId", invoiceId).containsKey("sha256");
        // No secret and no message content in the journal.
        assertThat(events.toString()).doesNotContain(key).doesNotContain("Client Synthetique");

        // Stopped: the route closes at once.
        ResponseEntity<Map> stopped = send(company, HttpMethod.POST, "/api/v1/connectivity/listeners/" + id + "/stop", null);
        assertThat(stopped.getBody()).containsEntry("state", "STOPPED").containsEntry("desiredState", "STOPPED");
        assertThat(inbound(id, key, MediaType.APPLICATION_JSON, invoice()).getStatusCode())
                .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(events(id)).extracting(e -> e.get("eventType")).contains("STOP_REQUESTED", "STOPPED");
    }

    @Test
    void aMessageOverTheSizeLimitIsRefused() {
        Map listener = createListener("HTTP petit", activeChannel("HTTPS petit"), entryPoint("PETIT", "JSON"),
                Map.of("maxBytes", 200));
        String id = (String) listener.get("id");
        start(id);
        ResponseEntity<Map> tooLarge = inbound(id, (String) listener.get("apiKey"), MediaType.APPLICATION_JSON, invoice());
        assertThat(tooLarge.getStatusCode()).isEqualTo(HttpStatus.CONTENT_TOO_LARGE);
        assertThat(tooLarge.getBody()).containsEntry("errorCode", "INBOUND_TOO_LARGE");
    }

    @Test
    void aStartIsRefusedWhileItsPreconditionsFail() {
        UUID channel = channel("HTTPS brouillon", "IN", "DRAFT");
        Map listener = createListener("HTTP brouillon", channel, entryPoint("XML-IN", "XML"));
        String id = (String) listener.get("id");

        ResponseEntity<Map> refused = send(company, HttpMethod.POST, "/api/v1/connectivity/listeners/" + id + "/start", null);
        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(refused.getBody()).containsEntry("errorCode", "LISTENER_NOT_READY");
        assertThat((List<Map>) refused.getBody().get("fieldErrors")).extracting(f -> f.get("field"))
                .contains("channel.status", "entryPoint.inputFormat");
        assertThat(get("/api/v1/connectivity/listeners/" + id)).containsEntry("state", "CONFIGURED")
                .containsEntry("desiredState", "STOPPED");
        assertThat(events(id)).extracting(e -> e.get("eventType")).contains("START_REFUSED");
        assertThat(supervisor.isLocal(UUID.fromString(id))).isFalse();
    }

    @Test
    void concurrentStartsStartOnce() throws Exception {
        Map listener = createListener("HTTP concurrent", activeChannel("HTTPS concurrent"), entryPoint("CONC", "JSON"));
        String id = (String) listener.get("id");
        List<Future<ResponseEntity<Map>>> calls = new ArrayList<>();
        try (ExecutorService pool = Executors.newFixedThreadPool(8)) {
            for (int i = 0; i < 8; i++) {
                calls.add(pool.submit(() -> send(company, HttpMethod.POST,
                        "/api/v1/connectivity/listeners/" + id + "/start", null)));
            }
            for (Future<ResponseEntity<Map>> call : calls) {
                assertThat(call.get().getStatusCode()).isEqualTo(HttpStatus.OK);
            }
        }
        assertThat(events(id)).filteredOn(e -> "STARTED".equals(e.get("eventType"))).hasSize(1);
        assertThat(events(id)).filteredOn(e -> "START_REQUESTED".equals(e.get("eventType"))).hasSize(1);
        assertThat(get("/api/v1/connectivity/listeners/" + id)).containsEntry("state", "RUNNING");
    }

    @Test
    void aRestartedProcessRestoresTheListenersLeftRunning() {
        Map listener = createListener("HTTP reprise", activeChannel("HTTPS reprise"), entryPoint("REPRISE", "JSON"));
        String id = (String) listener.get("id");
        String key = (String) listener.get("apiKey");
        start(id);

        // Process shutdown: the routes close, the desired state stays RUNNING.
        supervisor.stop();
        assertThat(get("/api/v1/connectivity/listeners/" + id)).containsEntry("state", "STOPPED")
                .containsEntry("desiredState", "RUNNING");
        assertThat(inbound(id, key, MediaType.APPLICATION_JSON, invoice()).getStatusCode())
                .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);

        // Next start of the process: reconciliation brings it back.
        supervisor.start();
        waitFor("/api/v1/connectivity/listeners/" + id, l -> "RUNNING".equals(l.get("state")));
        assertThat(events(id)).extracting(e -> e.get("eventType")).contains("NODE_SHUTDOWN", "RESTORED");
        assertThat(inbound(id, key, MediaType.APPLICATION_JSON, invoice()).getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
    }

    @Test
    void aLostRouteIsSeenByTheHeartbeat() {
        Map listener = createListener("HTTP santé", activeChannel("HTTPS santé"), entryPoint("SANTE", "JSON"));
        String id = (String) listener.get("id");
        start(id);
        // The resource disappears behind the supervisor's back.
        adapter.stop(new ListenerAdapter.Handle(Listener.Kind.HTTP_INBOUND, UUID.fromString(id), company, null));
        Map degraded = waitFor("/api/v1/connectivity/listeners/" + id, l -> "DEGRADED".equals(l.get("state")));
        assertThat((Map) degraded.get("lastError")).containsEntry("code", "ROUTE_MISSING");
        assertThat(events(id)).extracting(e -> e.get("eventType")).contains("HEALTH_DEGRADED");
        send(company, HttpMethod.POST, "/api/v1/connectivity/listeners/" + id + "/stop", null);
    }

    @Test
    void anotherCompanySeesNothingAndControlsNothing() {
        Map listener = createListener("HTTP isolé", activeChannel("HTTPS isolé"), entryPoint("ISOLE", "JSON"));
        String id = (String) listener.get("id");
        assertThat(send(other, HttpMethod.GET, "/api/v1/connectivity/listeners/" + id, null).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(send(other, HttpMethod.POST, "/api/v1/connectivity/listeners/" + id + "/start", null).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(send(other, HttpMethod.GET, "/api/v1/connectivity/listeners/" + id + "/events", null).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        List<Map> otherListeners = http.get().uri("/api/v1/connectivity/listeners")
                .header("X-Mystix-Company-Id", other.toString()).retrieve().body(List.class);
        assertThat(otherListeners).extracting(l -> l.get("id")).doesNotContain(id);
    }

    @Test
    void unknownKindsAndChannelsInUseAreRefused() {
        UUID channel = activeChannel("HTTPS utilisé");
        UUID entry = entryPoint("UTILISE", "JSON");
        // A kind no adapter knows is refused at the door.
        ResponseEntity<Map> unknown = send(company, HttpMethod.POST, "/api/v1/connectivity/listeners", Map.of(
                "name", "FTP pas encore", "kind", "FTP_POLLING", "channelId", channel, "entryPointId", entry));
        assertThat(unknown.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(unknown.getBody()).containsEntry("errorCode", "VALIDATION_FAILED");

        createListener("HTTP utilisé", channel, entry);
        ResponseEntity<Map> delete = send(company, HttpMethod.DELETE, "/api/v1/channels/" + channel, null);
        assertThat(delete.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(delete.getBody()).containsEntry("errorCode", "CHANNEL_IN_USE");
    }

    // ---------- helpers ----------

    private UUID activeChannel(String name) {
        return channel(name, "IN", "ACTIVE");
    }

    private UUID channel(String name, String direction, String status) {
        ResponseEntity<Map> r = send(company, HttpMethod.POST, "/api/v1/channels", Map.of("name", name,
                "protocol", "HTTPS", "direction", direction, "status", status, "usedByMystix", true,
                "settings", Map.of()));
        assertThat(r.getStatusCode()).as(String.valueOf(r.getBody())).isEqualTo(HttpStatus.CREATED);
        return UUID.fromString((String) r.getBody().get("id"));
    }

    private UUID entryPoint(String code, String format) {
        ResponseEntity<Map> r = send(company, HttpMethod.POST, "/api/v1/connectivity/entry-points", Map.of(
                "code", code, "name", "Factures " + code, "flowId", flowId, "documentType", "INVOICE",
                "inputFormat", format));
        assertThat(r.getStatusCode()).as(String.valueOf(r.getBody())).isEqualTo(HttpStatus.CREATED);
        return UUID.fromString((String) r.getBody().get("id"));
    }

    private Map createListener(String name, UUID channel, UUID entry) {
        return createListener(name, channel, entry, Map.of());
    }

    private Map createListener(String name, UUID channel, UUID entry, Map<String, Object> settings) {
        Map<String, Object> body = new HashMap<>(Map.of("name", name, "kind", "HTTP_INBOUND", "channelId", channel,
                "entryPointId", entry, "settings", settings));
        ResponseEntity<Map> r = send(company, HttpMethod.POST, "/api/v1/connectivity/listeners", body);
        assertThat(r.getStatusCode()).as(String.valueOf(r.getBody())).isEqualTo(HttpStatus.CREATED);
        return r.getBody();
    }

    private void start(String id) {
        ResponseEntity<Map> r = send(company, HttpMethod.POST, "/api/v1/connectivity/listeners/" + id + "/start", null);
        assertThat(r.getStatusCode()).as(String.valueOf(r.getBody())).isEqualTo(HttpStatus.OK);
    }

    /** The reference invoice with a new number, issued today so it is not held as backdated. */
    private synchronized String invoice() {
        String number = "FA-CNX-" + System.nanoTime() % 1_000_000_000 + "-" + (++counter);
        LocalDate today = LocalDate.now(ZoneId.of("Africa/Casablanca"));
        return invoiceJson.replace(REFERENCE_NUMBER, number)
                .replaceAll("\"issueDate\"\\s*:\\s*\"[0-9-]+\"", "\"issueDate\": \"" + today + "\"")
                .replaceAll("\"dueDate\"\\s*:\\s*\"[0-9-]+\"", "\"dueDate\": \"" + today.plusDays(30) + "\"");
    }

    private ResponseEntity<Map> inbound(String listenerId, String key, MediaType type, String body) {
        var request = http.post().uri("/inbound/http/" + listenerId).contentType(type);
        if (key != null) {
            request = request.header("X-Mystix-Api-Key", key);
        }
        return request.body(body.getBytes(StandardCharsets.UTF_8)).retrieve().toEntity(Map.class);
    }

    private ResponseEntity<Map> send(UUID tenant, HttpMethod method, String uri, Object body) {
        var request = http.method(method).uri(uri).header("X-Mystix-Company-Id", tenant.toString());
        if (body != null) {
            request = request.contentType(MediaType.APPLICATION_JSON).body(body);
        }
        return request.retrieve().toEntity(Map.class);
    }

    private Map get(String uri) {
        return http.get().uri(uri).header("X-Mystix-Company-Id", company.toString()).retrieve().body(Map.class);
    }

    private List<Map> events(String listenerId) {
        return http.get().uri("/api/v1/connectivity/listeners/" + listenerId + "/events")
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
