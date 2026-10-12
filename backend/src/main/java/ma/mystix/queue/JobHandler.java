package ma.mystix.queue;

/**
 * Runs one kind of job (ADR-0003). A handler must be idempotent: a job can run again after a crash or an expired
 * lease. Throwing a {@link ma.mystix.shared.error.MystixException} whose error code is not retryable sends the job to
 * DEAD at once; any other exception is retried with backoff.
 */
public interface JobHandler {

    /** The {@link Job#kind()} this handler runs. */
    String kind();

    void handle(Job job);
}
