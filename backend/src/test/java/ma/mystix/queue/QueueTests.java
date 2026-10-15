package ma.mystix.queue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import ma.mystix.TestcontainersConfiguration;
import ma.mystix.shared.error.ErrorCode;
import ma.mystix.shared.error.MystixException;
import ma.mystix.shared.error.RetryLaterException;
import ma.mystix.tenant.CompanyService;
import ma.mystix.tenant.Ice;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.client.RestClient;

/**
 * The PostgreSQL work queue (ADR-0003): idempotent enqueue, retries with backoff, DEAD and replay, expired leases,
 * per-lane cap and fairness, no job run twice under concurrent workers, tenant isolation. The worker is off: the
 * tests drive the queue by hand.
 */
@Import({TestcontainersConfiguration.class, QueueTests.Handlers.class})
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "mystix.queue.worker-enabled=false", "mystix.queue.backoff-base=200ms", "mystix.queue.backoff-max=400ms",
        "mystix.queue.max-attempts=3", "mystix.queue.default-max-running=2", "mystix.queue.concurrency=8",
        "mystix.queue.default-max-queued=3"})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@SuppressWarnings({"unchecked", "rawtypes"})
class QueueTests {

    static final Map<UUID, AtomicInteger> RUNS = new ConcurrentHashMap<>();

    @TestConfiguration
    static class Handlers {

        @Bean
        JobHandler okHandler() {
            return handler("TEST_OK", job -> { });
        }

        /** Fails as many times as the payload says, then succeeds. */
        @Bean
        JobHandler flakyHandler() {
            return handler("TEST_FLAKY", job -> {
                int failures = ((Number) job.payload().get("failures")).intValue();
                if (job.attempts() <= failures) {
                    throw new IllegalStateException("transient failure " + job.attempts());
                }
            });
        }

        @Bean
        JobHandler permanentHandler() {
            return handler("TEST_PERMANENT", job -> {
                throw new MystixException(ErrorCode.VALIDATION_FAILED, "bad input, retrying will not help");
            });
        }

        private static JobHandler handler(String kind, java.util.function.Consumer<Job> body) {
            return new JobHandler() {
                @Override
                public String kind() {
                    return kind;
                }

                @Override
                public void handle(Job job) {
                    RUNS.computeIfAbsent(job.subjectId(), k -> new AtomicInteger()).incrementAndGet();
                    body.accept(job);
                }
            };
        }
    }

    @Value("${local.server.port}")
    int port;

    @Autowired
    CompanyService companies;

    @Autowired
    MessageQueue queue;

    @Autowired
    QueueWorker worker;

    @Autowired
    JdbcClient jdbc;

    RestClient http;
    UUID company;
    UUID other;

    @BeforeAll
    void companies() {
        http = RestClient.builder().baseUrl("http://localhost:" + port)
                .defaultStatusHandler(status -> true, (request, response) -> { }).build();
        company = companies.register(new Ice("000000070000070"), "File Fictive Test SARL", null).id();
        other = companies.register(new Ice("000000080000080"), "Autre File Fictive SARL", null).id();
    }

    @BeforeEach
    void emptyQueue() {
        // Each test starts from an empty queue, so claims only see its own jobs.
        jdbc.sql("DELETE FROM queue_job").update();
        jdbc.sql("DELETE FROM queue_lane").update();
    }

    @Test
    void enqueueIsIdempotentAndAJobRunsToDone() {
        UUID subject = UUID.randomUUID();
        Job first = enqueue("TEST_OK", Job.companyLane(company), subject, Map.of(), 5);
        Job second = enqueue("TEST_OK", Job.companyLane(company), subject, Map.of(), 5);
        assertThat(second.id()).isEqualTo(first.id());
        assertThat(first.status()).isEqualTo(Job.Status.READY);

        assertThat(worker.runOnce()).isEqualTo(1);
        Job done = queue.get(company, first.id());
        assertThat(done.status()).isEqualTo(Job.Status.DONE);
        assertThat(done.attempts()).isEqualTo(1);
        assertThat(done.finishedAt()).isNotNull();
        assertThat(queue.stats(company).done()).isEqualTo(1);
    }

    @Test
    void transientFailuresAreRetriedWithBackoff() throws InterruptedException {
        Job job = enqueue("TEST_FLAKY", Job.companyLane(company), UUID.randomUUID(), Map.of("failures", 2), 5);

        worker.runOnce();
        Job afterFirst = queue.get(company, job.id());
        assertThat(afterFirst.status()).isEqualTo(Job.Status.READY);
        assertThat(afterFirst.availableAt()).isAfter(afterFirst.updatedAt().minusNanos(1));
        assertThat(afterFirst.lastError()).containsEntry("errorCode", "INTERNAL_ERROR").containsEntry("attempt", 1);
        // Not due yet: nothing to claim right away.
        assertThat(queue.claim("nobody", 10)).isEmpty();

        Job finished = runUntilFinished(job.id());
        assertThat(finished.status()).isEqualTo(Job.Status.DONE);
        assertThat(finished.attempts()).isEqualTo(3);
    }

    @Test
    void retryableFailuresEndInDeadAfterTheLastAttempt() throws InterruptedException {
        Job job = enqueue("TEST_FLAKY", Job.companyLane(company), UUID.randomUUID(), Map.of("failures", 99), 5);
        Job dead = runUntilFinished(job.id());
        assertThat(dead.status()).isEqualTo(Job.Status.DEAD);
        assertThat(dead.attempts()).isEqualTo(3);
        assertThat(queue.stats(company).dead()).isEqualTo(1);
    }

    @Test
    void permanentFailureIsDeadAtOnceAndCanBeReplayed() {
        UUID subject = UUID.randomUUID();
        Job job = enqueue("TEST_PERMANENT", Job.companyLane(company), subject, Map.of(), 5);
        worker.runOnce();
        Job dead = queue.get(company, job.id());
        assertThat(dead.status()).isEqualTo(Job.Status.DEAD);
        assertThat(dead.attempts()).isEqualTo(1);
        assertThat(dead.lastError()).containsEntry("errorCode", "VALIDATION_FAILED").containsEntry("retryable", false);

        // Replay through the API: back to READY with fresh attempts, then dead again (the input is still bad).
        ResponseEntity<Map> replayed = post(company, "/api/v1/queue/jobs/" + job.id() + "/replay");
        assertThat(replayed.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(replayed.getBody()).containsEntry("status", "READY").containsEntry("attempts", 0);
        worker.runOnce();
        assertThat(RUNS.get(subject).get()).isEqualTo(2);

        // Only a DEAD job can be replayed; another company sees nothing.
        Job ok = enqueue("TEST_OK", Job.companyLane(company), UUID.randomUUID(), Map.of(), 5);
        assertThat(post(company, "/api/v1/queue/jobs/" + ok.id() + "/replay").getBody())
                .containsEntry("errorCode", "QUEUE_JOB_NOT_DEAD");
        assertThat(post(other, "/api/v1/queue/jobs/" + job.id() + "/replay").getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThatThrownBy(() -> queue.get(other, job.id())).isInstanceOf(MystixException.class);
        Map stats = http.get().uri("/api/v1/queue/stats").header("X-Mystix-Company-Id", other.toString())
                .retrieve().body(Map.class);
        assertThat(stats).containsEntry("dead", 0).containsEntry("ready", 0);
    }

    @Test
    void unknownKindGoesToDead() {
        Job job = enqueue("NOBODY_RUNS_THIS", Job.companyLane(company), UUID.randomUUID(), Map.of(), 5);
        worker.runOnce();
        assertThat(queue.get(company, job.id()).lastError()).containsEntry("errorCode", "QUEUE_NO_HANDLER");
    }

    @Test
    void expiredLeaseReleasesTheJob() {
        Job job = enqueue("TEST_OK", Job.companyLane(company), UUID.randomUUID(), Map.of(), 5);
        assertThat(queue.claim("crashed-worker", 1)).hasSize(1);
        jdbc.sql("UPDATE queue_job SET locked_until = :past WHERE id = :id")
                .param("past", OffsetDateTime.now().minusMinutes(1)).param("id", job.id()).update();

        assertThat(queue.releaseExpired()).isEqualTo(1);
        Job released = queue.get(company, job.id());
        assertThat(released.status()).isEqualTo(Job.Status.READY);
        assertThat(released.lastError()).containsEntry("errorCode", "QUEUE_LEASE_EXPIRED");
        // The crashed worker can no longer complete it; a live one can.
        assertThat(queue.complete(job, "crashed-worker")).isFalse();
        assertThat(worker.runOnce()).isEqualTo(1);
        assertThat(queue.get(company, job.id()).status()).isEqualTo(Job.Status.DONE);
    }

    @Test
    void lanesAreServedFairlyWithinTheirCap() {
        String busy = Job.flowLane(UUID.randomUUID());
        String quiet = Job.flowLane(UUID.randomUUID());
        for (int i = 0; i < 50; i++) {
            enqueue("TEST_OK", busy, UUID.randomUUID(), Map.of(), 5);
        }
        enqueue("TEST_OK", quiet, UUID.randomUUID(), Map.of(), 5);
        enqueue("TEST_OK", quiet, UUID.randomUUID(), Map.of(), 5);

        List<Job> claimed = queue.claim("w1", 8);
        // Cap of 2 per lane: the busy flow cannot take the slots the quiet one needs.
        assertThat(claimed).filteredOn(j -> j.lane().equals(busy)).hasSize(2);
        assertThat(claimed).filteredOn(j -> j.lane().equals(quiet)).hasSize(2);
        assertThat(queue.claim("w2", 8)).isEmpty();

        // A lane setting overrides the default cap; a paused lane is skipped.
        claimed.forEach(j -> queue.complete(j, "w1"));
        jdbc.sql("INSERT INTO queue_lane (lane, company_id, max_running, paused, updated_at) VALUES (:l, :c, 5, FALSE, now())")
                .param("l", busy).param("c", company).update();
        assertThat(queue.claim("w3", 8)).hasSize(5);
        jdbc.sql("UPDATE queue_lane SET paused = TRUE WHERE lane = :l").param("l", busy).update();
        assertThat(queue.claim("w4", 8)).isEmpty();
    }

    @Test
    void aLaneAtItsQuotaRefusesNewWorkUntilAJobFinishes() {
        String busy = Job.flowLane(UUID.randomUUID());
        String quiet = Job.flowLane(UUID.randomUUID());
        jdbc.sql("INSERT INTO queue_lane (lane, company_id, max_running, max_queued, updated_at) VALUES (:l, :c, 2, 2, now())")
                .param("l", busy).param("c", company).update();
        queue.admit(company, busy);
        enqueue("TEST_OK", busy, UUID.randomUUID(), Map.of(), 5);
        enqueue("TEST_OK", busy, UUID.randomUUID(), Map.of(), 5);

        // RUNNING jobs count as unfinished: claiming does not make room.
        List<Job> claimed = queue.claim("w", 1);
        assertThatThrownBy(() -> queue.admit(company, busy))
                .isInstanceOfSatisfying(RetryLaterException.class, e -> {
                    assertThat(e.errorCode()).isEqualTo(ErrorCode.QUEUE_QUOTA_EXCEEDED);
                    assertThat(e.retryAfter()).isPositive();
                });
        // Another lane, and the lane of another company, keep their own quota.
        queue.admit(company, quiet);
        queue.admit(other, Job.companyLane(other));

        queue.complete(claimed.getFirst(), "w");
        queue.admit(company, busy);
    }

    @Test
    void theDefaultQuotaAppliesWhenTheLaneHasNoSetting() {
        // Default from the test properties: 3 unfinished jobs.
        String lane = Job.companyLane(company);
        for (int i = 0; i < 3; i++) {
            queue.admit(company, lane);
            enqueue("TEST_OK", lane, UUID.randomUUID(), Map.of(), 5);
        }
        assertThatThrownBy(() -> queue.admit(company, lane)).isInstanceOf(RetryLaterException.class);
        // A lane row without max_queued still uses the default.
        jdbc.sql("INSERT INTO queue_lane (lane, company_id, max_running, updated_at) VALUES (:l, :c, 9, now())")
                .param("l", lane).param("c", company).update();
        assertThatThrownBy(() -> queue.admit(company, lane)).isInstanceOf(RetryLaterException.class);
    }

    @Test
    void higherPriorityRunsFirstInALane() {
        String lane = Job.flowLane(UUID.randomUUID());
        Job low = enqueue("TEST_OK", lane, UUID.randomUUID(), Map.of(), 1);
        Job high = enqueue("TEST_OK", lane, UUID.randomUUID(), Map.of(), 9);
        List<Job> claimed = queue.claim("w", 1);
        assertThat(claimed).extracting(Job::id).containsExactly(high.id());
        assertThat(low.status()).isEqualTo(Job.Status.READY);
    }

    @Test
    void concurrentWorkersNeverRunAJobTwiceNorExceedTheLaneCap() throws Exception {
        List<String> lanes = List.of(Job.flowLane(UUID.randomUUID()), Job.flowLane(UUID.randomUUID()),
                Job.flowLane(UUID.randomUUID()), Job.flowLane(UUID.randomUUID()));
        Set<UUID> subjects = ConcurrentHashMap.newKeySet();
        for (int i = 0; i < 200; i++) {
            UUID subject = UUID.randomUUID();
            subjects.add(subject);
            enqueue("TEST_OK", lanes.get(i % lanes.size()), subject, Map.of(), 5);
        }
        Map<UUID, AtomicInteger> processed = new ConcurrentHashMap<>();
        AtomicInteger capBreaches = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(8);
        List<Future<?>> workers = new ArrayList<>();
        for (int w = 0; w < 8; w++) {
            String name = "worker-" + w;
            workers.add(pool.submit(() -> {
                int idle = 0;
                while (idle < 20) {
                    List<Job> jobs = queue.claim(name, 3);
                    if (jobs.isEmpty()) {
                        idle++;
                        Thread.sleep(5);
                        continue;
                    }
                    idle = 0;
                    Integer worst = jdbc.sql("""
                                    SELECT COALESCE(max(n), 0) FROM (SELECT count(*) AS n FROM queue_job
                                    WHERE status = 'RUNNING' GROUP BY lane) t
                                    """).query(Integer.class).single();
                    if (worst > 2) {
                        capBreaches.incrementAndGet();
                    }
                    for (Job job : jobs) {
                        processed.computeIfAbsent(job.subjectId(), k -> new AtomicInteger()).incrementAndGet();
                        assertThat(queue.complete(job, name)).isTrue();
                    }
                }
                return null;
            }));
        }
        for (Future<?> f : workers) {
            f.get();
        }
        pool.shutdown();

        assertThat(processed.keySet()).containsExactlyInAnyOrderElementsOf(subjects);
        assertThat(processed.values()).allMatch(n -> n.get() == 1);
        assertThat(capBreaches.get()).isZero();
        assertThat(queue.stats(company).done()).isEqualTo(200);
    }

    private Job enqueue(String kind, String lane, UUID subject, Map<String, Object> payload, int priority) {
        return queue.enqueue(new Job.New(company, lane, kind, subject, payload, priority));
    }

    private Job runUntilFinished(UUID id) throws InterruptedException {
        for (int i = 0; i < 100; i++) {
            worker.runOnce();
            Job job = queue.get(company, id);
            if (job.status() == Job.Status.DONE || job.status() == Job.Status.DEAD) {
                return job;
            }
            Thread.sleep(20);
        }
        throw new AssertionError("job " + id + " did not finish");
    }

    private ResponseEntity<Map> post(UUID tenant, String uri) {
        return http.post().uri(uri).header("X-Mystix-Company-Id", tenant.toString()).retrieve().toEntity(Map.class);
    }
}
