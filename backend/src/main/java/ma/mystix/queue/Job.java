package ma.mystix.queue;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

/**
 * One step of the processing of one message (ADR-0003).
 *
 * @param lane      the flow (or company) it belongs to, for fairness and per-lane concurrency
 * @param kind      the step to run; one {@link JobHandler} per kind
 * @param subjectId the message the step works on; (kind, subjectId) is unique
 * @param attempts  attempts started so far, this one included while RUNNING
 * @param lastError errorCode, stage, retryable, message and attempt of the last failure
 */
public record Job(UUID id, UUID companyId, String lane, String kind, UUID subjectId, Map<String, Object> payload,
                  int priority, Status status, int attempts, int maxAttempts, OffsetDateTime availableAt,
                  String lockedBy, OffsetDateTime lockedUntil, Map<String, Object> lastError,
                  OffsetDateTime createdAt, OffsetDateTime updatedAt, OffsetDateTime finishedAt) {

    public enum Status { READY, RUNNING, DONE, DEAD }

    /** Lane of a flow: its jobs share one concurrency cap and are served in turn with the other lanes. */
    public static String flowLane(UUID flowId) {
        return "flow:" + flowId;
    }

    /** Lane of a company, for work that belongs to no flow. */
    public static String companyLane(UUID companyId) {
        return "company:" + companyId;
    }

    /**
     * A job to enqueue.
     *
     * @param priority 0 (lowest) to 9 (highest); portal actions above batch work
     */
    public record New(UUID companyId, String lane, String kind, UUID subjectId, Map<String, Object> payload,
                      int priority) {
    }

    /** Why a run failed; {@code retryable} false sends the job to DEAD at once. */
    public record Failure(String errorCode, String stage, boolean retryable, String message) {
    }
}
