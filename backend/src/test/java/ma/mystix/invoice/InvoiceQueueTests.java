package ma.mystix.invoice;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;

import ma.mystix.TestClockConfiguration;
import ma.mystix.TestcontainersConfiguration;
import ma.mystix.clearance.ClearanceGateway;
import ma.mystix.clearance.ClearanceRequest;
import ma.mystix.clearance.ClearanceResult;
import ma.mystix.clearance.SimulatedClearanceGateway;
import ma.mystix.tenant.CompanyService;
import ma.mystix.tenant.Ice;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.client.RestClient;

/**
 * Clearance through the queue (ADR-0003, Lot 4): asynchronous submission with an immediate 202, and retries after a
 * gateway failure, until DEAD. The gateway is the simulated one, wrapped to fail on chosen invoice numbers.
 */
@Import({TestcontainersConfiguration.class, TestClockConfiguration.class, InvoiceQueueTests.FlakyGateway.class})
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "mystix.queue.poll-interval=50ms", "mystix.queue.backoff-base=50ms", "mystix.queue.backoff-max=100ms",
        "mystix.queue.max-attempts=3"})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@SuppressWarnings({"unchecked", "rawtypes"})
class InvoiceQueueTests {

    private static final String REFERENCE_NUMBER = "FA-2026-000123";
    static final Map<String, AtomicInteger> CALLS = new ConcurrentHashMap<>();

    /** FA-FLAKY-* fails twice then clears; FA-DOWN-* always fails; anything else is the simulated gateway. */
    @TestConfiguration
    static class FlakyGateway {

        @Bean
        @Primary
        ClearanceGateway flakyClearanceGateway(Clock clock) {
            ClearanceGateway simulated = new SimulatedClearanceGateway(clock, "");
            return new ClearanceGateway() {
                @Override
                public ClearanceResult submit(ClearanceRequest request) {
                    int call = CALLS.computeIfAbsent(request.invoiceNumber(), k -> new AtomicInteger()).incrementAndGet();
                    if (request.invoiceNumber().startsWith("FA-DOWN")
                            || (request.invoiceNumber().startsWith("FA-FLAKY") && call <= 2)) {
                        throw new IllegalStateException("gateway unreachable (test)");
                    }
                    return simulated.submit(request);
                }

                @Override
                public boolean simulated() {
                    return true;
                }
            };
        }
    }

    @Value("${local.server.port}")
    int port;

    @Autowired
    CompanyService companies;

    @Autowired
    JdbcClient jdbc;

    RestClient http;
    UUID company;
    String validRequest;

    @BeforeAll
    void setUp() throws IOException {
        http = RestClient.builder().baseUrl("http://localhost:" + port)
                .defaultStatusHandler(status -> true, (request, response) -> { }).build();
        // Same ICE as the seller of the reference fixture.
        // The database may be shared with InvoiceApiTests, which registers the same ICE.
        company = companies.list().stream().filter(c -> c.ice().equals(new Ice("000000001000011"))).findFirst()
                .orElseGet(() -> companies.register(new Ice("000000001000011"), "Fournisseur File Synthetique SARL",
                        null)).id();
        try (InputStream in = getClass().getClassLoader().getResourceAsStream("fixtures/api/invoice-basic.request.json")) {
            validRequest = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void asynchronousSubmissionAnswers202ThenClears() {
        ResponseEntity<byte[]> response = submit("FA-ASYNC-1", true);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(response.getHeaders().getFirst("Preference-Applied")).isEqualTo("respond-async");
        assertThat(response.getHeaders().getFirst("X-Mystix-Status")).isEqualTo("VALIDATED");
        assertThat(response.getHeaders().getLocation()).isNotNull();
        assertThat(response.getBody()).isNotEmpty();
        String id = response.getHeaders().getFirst("X-Mystix-Invoice-Id");

        Map invoice = waitForInvoice(id, i -> "CLEARED".equals(i.get("status")));
        assertThat(((Map) invoice.get("clearance")).get("simulated")).isEqualTo(true);

        // Same number again: replayed, no second job.
        assertThat(submit("FA-ASYNC-1", true).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(jobsFor(id, null)).hasSize(1);
    }

    @Test
    void synchronousSubmissionIsUnchanged() {
        ResponseEntity<byte[]> response = submit("FA-SYNC-1", false);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getHeaders().getFirst("X-Mystix-Status")).isEqualTo("CLEARED");
        assertThat(jobsFor(response.getHeaders().getFirst("X-Mystix-Invoice-Id"), null)).isEmpty();
    }

    @Test
    void gatewayFailureIsRetriedByTheQueue() {
        ResponseEntity<byte[]> response = submit("FA-FLAKY-1", false);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getHeaders().getFirst("X-Mystix-Status")).isEqualTo("VALIDATED");
        String id = response.getHeaders().getFirst("X-Mystix-Invoice-Id");

        Map invoice = waitForInvoice(id, i -> "CLEARED".equals(i.get("status")));
        assertThat((List<Map>) invoice.get("history")).extracting(e -> e.get("status")).contains("CLEARANCE_ERROR");
        // First call in the request, two in the queue (one failed, one cleared).
        assertThat(CALLS.get("FA-FLAKY-1").get()).isEqualTo(3);
        assertThat(jobsFor(id, "DONE")).hasSize(1);
    }

    @Test
    void persistentFailureEndsInDeadAndStaysValidated() throws InterruptedException {
        ResponseEntity<byte[]> response = submit("FA-DOWN-1", true);
        String id = response.getHeaders().getFirst("X-Mystix-Invoice-Id");
        for (int i = 0; i < 100 && jobsFor(id, "DEAD").isEmpty(); i++) {
            Thread.sleep(100);
        }
        List<Map> dead = jobsFor(id, "DEAD");
        assertThat(dead).hasSize(1);
        assertThat(dead.getFirst()).containsEntry("attempts", 3);
        assertThat((Map) dead.getFirst().get("lastError")).containsEntry("retryable", true);
        assertThat(http.get().uri("/api/v1/invoices/" + id).header("X-Mystix-Company-Id", company.toString())
                .retrieve().body(Map.class)).containsEntry("status", "VALIDATED");
    }

    @Test
    void asynchronousSubmissionOverTheFlowQuotaAnswers429AndStoresNothing() throws InterruptedException {
        // Learn the flow's lane, then pause it with room for one more job, so the next queued job stays unfinished.
        ResponseEntity<byte[]> first = submit("FA-QUOTA-1", false);
        String lane = "flow:" + first.getHeaders().getFirst("X-Mystix-Flow-Id");
        jdbc.sql("INSERT INTO queue_lane (lane, company_id, max_running, max_queued, paused, updated_at) "
                        + "VALUES (:l, :c, 4, 1, TRUE, now())")
                .param("l", lane).param("c", company).update();
        // Jobs left by other tests of this class (a DEAD-bound retry, say) may still be unfinished in this lane: once
        // none runs, the paused lane's count is stable and the quota leaves room for exactly one more.
        for (int i = 0; i < 100 && jdbc.sql("SELECT count(*) FROM queue_job WHERE lane = :l AND status = 'RUNNING'")
                .param("l", lane).query(Long.class).single() > 0; i++) {
            Thread.sleep(100);
        }
        jdbc.sql("UPDATE queue_lane SET max_queued = 1 + (SELECT count(*) FROM queue_job WHERE lane = :l "
                        + "AND status IN ('READY', 'RUNNING')) WHERE lane = :l")
                .param("l", lane).update();
        try {
            assertThat(submit("FA-QUOTA-2", true).getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);

            ResponseEntity<byte[]> refused = submit("FA-QUOTA-3", true);
            assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
            assertThat(refused.getHeaders().getFirst("Retry-After")).isEqualTo("30");
            assertThat(new String(refused.getBody(), StandardCharsets.UTF_8))
                    .contains("\"errorCode\":\"QUEUE_QUOTA_EXCEEDED\"").contains("\"retryable\":true");
            // A synchronous submission does not wait in the queue: the quota does not apply.
            assertThat(submit("FA-QUOTA-4", false).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        } finally {
            jdbc.sql("DELETE FROM queue_lane WHERE lane = :l").param("l", lane).update();
        }
        // Nothing was stored for the refused invoice: sent again, it is a first acceptance, not a replay.
        ResponseEntity<byte[]> again = submit("FA-QUOTA-3", true);
        assertThat(again.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        waitForInvoice(again.getHeaders().getFirst("X-Mystix-Invoice-Id"), i -> "CLEARED".equals(i.get("status")));
    }

    private ResponseEntity<byte[]> submit(String number, boolean async) {
        var request = http.post().uri("/api/v1/invoices").header("X-Mystix-Company-Id", company.toString())
                .contentType(MediaType.APPLICATION_JSON).accept(MediaType.APPLICATION_XML, MediaType.APPLICATION_JSON);
        if (async) {
            request = request.header("Prefer", "respond-async");
        }
        return request.body(validRequest.replace(REFERENCE_NUMBER, number).getBytes(StandardCharsets.UTF_8))
                .retrieve().toEntity(byte[].class);
    }

    private Map waitForInvoice(String id, Predicate<Map> done) {
        Map invoice = null;
        for (int i = 0; i < 100; i++) {
            invoice = http.get().uri("/api/v1/invoices/" + id).header("X-Mystix-Company-Id", company.toString())
                    .retrieve().body(Map.class);
            if (done.test(invoice)) {
                return invoice;
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        throw new AssertionError("Invoice " + id + " did not reach the expected state: " + invoice);
    }

    private List<Map> jobsFor(String invoiceId, String status) {
        List<Map> jobs = http.get().uri("/api/v1/queue/jobs" + (status == null ? "" : "?status=" + status))
                .header("X-Mystix-Company-Id", company.toString()).retrieve().body(List.class);
        return jobs.stream().filter(j -> invoiceId.equals(j.get("subjectId"))).toList();
    }
}
