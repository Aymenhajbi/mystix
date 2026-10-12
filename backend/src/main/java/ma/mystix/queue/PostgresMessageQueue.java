package ma.mystix.queue;

import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import ma.mystix.shared.error.ErrorCode;
import ma.mystix.shared.error.MystixException;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@link MessageQueue} on PostgreSQL (ADR-0003): {@code FOR UPDATE SKIP LOCKED} so workers on several nodes never
 * take the same job, a transaction-scoped advisory lock per lane so the lane cap holds across nodes, a lease so a
 * crashed worker's jobs come back, and exponential backoff with jitter between attempts.
 */
@Component
class PostgresMessageQueue implements MessageQueue {

    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() { };

    private final JdbcClient jdbc;
    private final TransactionTemplate tx;
    private final QueueProperties properties;
    private final JsonMapper json;
    private final Clock clock;
    private final RowMapper<Job> rows;

    PostgresMessageQueue(JdbcClient jdbc, TransactionTemplate tx, QueueProperties properties, JsonMapper json,
                         Clock clock) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.properties = properties;
        this.json = json;
        this.clock = clock;
        this.rows = (rs, n) -> new Job(
                rs.getObject("id", UUID.class),
                rs.getObject("company_id", UUID.class),
                rs.getString("lane"),
                rs.getString("kind"),
                rs.getObject("subject_id", UUID.class),
                map(rs.getString("payload")),
                rs.getInt("priority"),
                Job.Status.valueOf(rs.getString("status")),
                rs.getInt("attempts"),
                rs.getInt("max_attempts"),
                rs.getObject("available_at", OffsetDateTime.class),
                rs.getString("locked_by"),
                rs.getObject("locked_until", OffsetDateTime.class),
                rs.getString("last_error") == null ? null : map(rs.getString("last_error")),
                rs.getObject("created_at", OffsetDateTime.class),
                rs.getObject("updated_at", OffsetDateTime.class),
                rs.getObject("finished_at", OffsetDateTime.class));
    }

    @Override
    @Transactional
    public Job enqueue(Job.New job) {
        if (job.priority() < 0 || job.priority() > 9) {
            throw new IllegalArgumentException("priority must be 0 to 9: " + job.priority());
        }
        OffsetDateTime now = now();
        jdbc.sql("""
                        INSERT INTO queue_job (id, company_id, lane, kind, subject_id, payload, priority, status,
                                               attempts, max_attempts, available_at, created_at, updated_at)
                        VALUES (:id, :c, :lane, :kind, :subject, CAST(:payload AS jsonb), :priority, 'READY', 0,
                                :max, :now, :now, :now)
                        ON CONFLICT (kind, subject_id) DO NOTHING
                        """)
                .param("id", UUID.randomUUID()).param("c", job.companyId()).param("lane", job.lane())
                .param("kind", job.kind()).param("subject", job.subjectId())
                .param("payload", json.writeValueAsString(job.payload() == null ? Map.of() : job.payload()))
                .param("priority", job.priority()).param("max", properties.maxAttempts()).param("now", now)
                .update();
        return jdbc.sql("SELECT * FROM queue_job WHERE kind = :kind AND subject_id = :subject")
                .param("kind", job.kind()).param("subject", job.subjectId()).query(rows).single();
    }

    private record ReadyLane(String lane, int maxRunning) {
    }

    @Override
    public List<Job> claim(String worker, int max) {
        if (max <= 0) {
            return List.of();
        }
        OffsetDateTime now = now();
        // Lanes with work due, highest priority then oldest first; paused lanes are skipped.
        List<ReadyLane> lanes = jdbc.sql("""
                        SELECT j.lane, COALESCE(l.max_running, :defaultMax) AS max_running
                        FROM queue_job j
                        LEFT JOIN queue_lane l ON l.lane = j.lane
                        WHERE j.status = 'READY' AND j.available_at <= :now AND NOT COALESCE(l.paused, FALSE)
                        GROUP BY j.lane, l.max_running
                        ORDER BY max(j.priority) DESC, min(j.available_at)
                        LIMIT :lanes
                        """)
                .param("now", now).param("defaultMax", properties.defaultMaxRunning())
                .param("lanes", properties.maxLanes())
                .query((rs, n) -> new ReadyLane(rs.getString("lane"), rs.getInt("max_running"))).list();
        if (lanes.isEmpty()) {
            return List.of();
        }
        // A fair share per lane: one busy flow cannot take every free slot from the others.
        int share = Math.max(1, (max + lanes.size() - 1) / lanes.size());
        List<Job> claimed = new ArrayList<>();
        for (ReadyLane lane : lanes) {
            int wanted = Math.min(share, max - claimed.size());
            if (wanted <= 0) {
                break;
            }
            List<Job> got = tx.execute(status -> claimLane(lane, wanted, worker, now));
            if (got != null) {
                claimed.addAll(got);
            }
        }
        return claimed;
    }

    private List<Job> claimLane(ReadyLane lane, int wanted, String worker, OffsetDateTime now) {
        // Another node is filling this lane right now: skip it this round rather than wait.
        Boolean locked = jdbc.sql("SELECT pg_try_advisory_xact_lock(hashtext('queue-lane:' || :lane))")
                .param("lane", lane.lane()).query(Boolean.class).single();
        if (!Boolean.TRUE.equals(locked)) {
            return List.of();
        }
        int running = jdbc.sql("""
                        SELECT count(*) FROM queue_job
                        WHERE lane = :lane AND status = 'RUNNING' AND locked_until > :now
                        """)
                .param("lane", lane.lane()).param("now", now).query(Integer.class).single();
        int n = Math.min(wanted, lane.maxRunning() - running);
        if (n <= 0) {
            return List.of();
        }
        return jdbc.sql("""
                        UPDATE queue_job
                        SET status = 'RUNNING', attempts = attempts + 1, locked_by = :worker, locked_until = :until,
                            updated_at = :now
                        WHERE id IN (SELECT id FROM queue_job
                                     WHERE lane = :lane AND status = 'READY' AND available_at <= :now
                                     ORDER BY priority DESC, available_at, created_at
                                     LIMIT :n
                                     FOR UPDATE SKIP LOCKED)
                        RETURNING *
                        """)
                .param("worker", worker).param("until", now.plus(properties.lease())).param("now", now)
                .param("lane", lane.lane()).param("n", n).query(rows).list();
    }

    @Override
    @Transactional
    public boolean complete(Job job, String worker) {
        OffsetDateTime now = now();
        return jdbc.sql("""
                        UPDATE queue_job
                        SET status = 'DONE', locked_by = NULL, locked_until = NULL, updated_at = :now,
                            finished_at = :now
                        WHERE id = :id AND status = 'RUNNING' AND locked_by = :worker
                        """)
                .param("id", job.id()).param("worker", worker).param("now", now).update() == 1;
    }

    @Override
    @Transactional
    public Job.Status fail(Job job, String worker, Job.Failure failure) {
        OffsetDateTime now = now();
        boolean dead = !failure.retryable() || job.attempts() >= job.maxAttempts();
        Map<String, Object> error = new LinkedHashMap<>();
        error.put("errorCode", failure.errorCode());
        error.put("stage", failure.stage());
        error.put("retryable", failure.retryable());
        error.put("message", failure.message() == null ? null : truncate(failure.message(), 1000));
        error.put("attempt", job.attempts());
        error.put("at", now.toString());
        int updated = jdbc.sql("""
                        UPDATE queue_job
                        SET status = :status, available_at = :available, locked_by = NULL, locked_until = NULL,
                            last_error = CAST(:error AS jsonb), updated_at = :now, finished_at = :finished
                        WHERE id = :id AND status = 'RUNNING' AND locked_by = :worker
                        """)
                .param("status", dead ? "DEAD" : "READY")
                .param("available", dead ? now : now.plus(backoff(job.attempts())))
                .param("error", json.writeValueAsString(error)).param("now", now)
                .param("finished", dead ? now : null).param("id", job.id()).param("worker", worker).update();
        if (updated == 0) {
            // The lease expired and the job was released or taken over: the new owner decides.
            return Job.Status.RUNNING;
        }
        return dead ? Job.Status.DEAD : Job.Status.READY;
    }

    /** base × 2^(attempt−1), capped, ±20 % so retries of a failed batch do not all fire at the same instant. */
    Duration backoff(int attempt) {
        long base = properties.backoffBase().toMillis();
        long raw = attempt <= 1 ? base : base << Math.min(attempt - 1, 20);
        long capped = Math.min(raw, properties.backoffMax().toMillis());
        double jitter = 0.8 + ThreadLocalRandom.current().nextDouble() * 0.4;
        return Duration.ofMillis(Math.max(1, (long) (capped * jitter)));
    }

    @Override
    @Transactional
    public int releaseExpired() {
        OffsetDateTime now = now();
        return jdbc.sql("""
                        UPDATE queue_job
                        SET status = CASE WHEN attempts >= max_attempts THEN 'DEAD' ELSE 'READY' END,
                            available_at = :now, locked_by = NULL, locked_until = NULL, updated_at = :now,
                            finished_at = CASE WHEN attempts >= max_attempts THEN CAST(:now AS timestamptz) END,
                            last_error = jsonb_build_object('errorCode', 'QUEUE_LEASE_EXPIRED', 'stage', 'QUEUE',
                                                            'retryable', TRUE, 'attempt', attempts,
                                                            'message', 'Worker ' || locked_by || ' did not finish in time')
                        WHERE status = 'RUNNING' AND locked_until < :now
                        """)
                .param("now", now).update();
    }

    @Override
    @Transactional
    public Job replay(UUID companyId, UUID jobId) {
        Job job = get(companyId, jobId);
        if (job.status() != Job.Status.DEAD) {
            throw new MystixException(ErrorCode.QUEUE_JOB_NOT_DEAD, "Job " + jobId + " is " + job.status());
        }
        OffsetDateTime now = now();
        jdbc.sql("""
                        UPDATE queue_job
                        SET status = 'READY', attempts = 0, available_at = :now, updated_at = :now, finished_at = NULL
                        WHERE id = :id AND company_id = :c AND status = 'DEAD'
                        """)
                .param("id", jobId).param("c", companyId).param("now", now).update();
        return get(companyId, jobId);
    }

    @Override
    public Job get(UUID companyId, UUID jobId) {
        return jdbc.sql("SELECT * FROM queue_job WHERE company_id = :c AND id = :id")
                .param("c", companyId).param("id", jobId).query(rows).optional()
                .orElseThrow(() -> new MystixException(ErrorCode.QUEUE_JOB_NOT_FOUND,
                        "No job " + jobId + " for company " + companyId));
    }

    @Override
    public List<Job> list(UUID companyId, Job.Status status, int limit) {
        return jdbc.sql("SELECT * FROM queue_job WHERE company_id = :c"
                        + (status == null ? "" : " AND status = :status")
                        + " ORDER BY created_at DESC LIMIT :limit")
                .param("c", companyId).param("status", status == null ? null : status.name())
                .param("limit", Math.clamp(limit, 1, 500)).query(rows).list();
    }

    @Override
    public QueueStats stats(UUID companyId) {
        return jdbc.sql("""
                        SELECT count(*) FILTER (WHERE status = 'READY')   AS ready,
                               count(*) FILTER (WHERE status = 'RUNNING') AS running,
                               count(*) FILTER (WHERE status = 'DONE')    AS done,
                               count(*) FILTER (WHERE status = 'DEAD')    AS dead,
                               min(available_at) FILTER (WHERE status = 'READY') AS oldest
                        FROM queue_job WHERE company_id = :c
                        """)
                .param("c", companyId)
                .query((rs, n) -> {
                    OffsetDateTime oldest = rs.getObject("oldest", OffsetDateTime.class);
                    Long age = oldest == null ? null : Math.max(0, Duration.between(oldest, now()).toSeconds());
                    return new QueueStats(rs.getLong("ready"), rs.getLong("running"), rs.getLong("done"),
                            rs.getLong("dead"), age);
                })
                .single();
    }

    private OffsetDateTime now() {
        return OffsetDateTime.now(clock);
    }

    private Map<String, Object> map(String text) {
        return text == null ? Map.of() : json.readValue(text, MAP);
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max);
    }
}
