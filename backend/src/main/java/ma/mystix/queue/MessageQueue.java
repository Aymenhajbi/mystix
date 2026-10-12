package ma.mystix.queue;

import java.util.List;
import java.util.UUID;

/**
 * The work queue (ADR-0003). The PostgreSQL implementation is the only one; this interface keeps a broker an adapter
 * away, should the review criteria of the ADR ever be met.
 */
public interface MessageQueue {

    /**
     * Adds a job in the caller's transaction, so the job exists if and only if the business change is committed.
     * Idempotent: a second call for the same (kind, subjectId) returns the existing job.
     */
    Job enqueue(Job.New job);

    /** Reserves up to {@code max} ready jobs for this worker, a fair share per lane, within each lane's cap. */
    List<Job> claim(String worker, int max);

    /** The run succeeded. False when the job is no longer this worker's (lease expired and taken over). */
    boolean complete(Job job, String worker);

    /** The run failed: back to READY after a backoff, or DEAD when not retryable or out of attempts. */
    Job.Status fail(Job job, String worker, Job.Failure failure);

    /** Releases the jobs whose lease expired (crashed worker); returns how many. */
    int releaseExpired();

    /** Sends a DEAD job of this company back to READY, attempts reset. */
    Job replay(UUID companyId, UUID jobId);

    Job get(UUID companyId, UUID jobId);

    List<Job> list(UUID companyId, Job.Status status, int limit);

    /** Depth per status and age of the oldest ready job, for this company. */
    QueueStats stats(UUID companyId);

    record QueueStats(long ready, long running, long done, long dead, Long oldestReadySeconds) {
    }
}
