package ma.mystix.connector;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

/**
 * A runtime resource that receives on a channel and hands messages to an entry point (ADR-0016).
 * {@code desiredState} is what an administrator asked for; {@code runtimeState} is what the engine observed.
 *
 * @param apiKeyHint last characters of the API key, to recognise it; the key itself is never stored
 */
public record Listener(UUID id, UUID companyId, String name, Kind kind, UUID channelId, UUID entryPointId,
                       Map<String, Object> settings, boolean hasApiKey, String apiKeyHint, boolean enabled,
                       DesiredState desiredState, RuntimeState runtimeState, String runtimeInstance,
                       OffsetDateTime lastHeartbeatAt, Map<String, Object> lastError, OffsetDateTime startedAt,
                       OffsetDateTime stoppedAt, OffsetDateTime lastActivityAt, long receivedCount,
                       long rejectedCount, String createdBy, OffsetDateTime createdAt, OffsetDateTime updatedAt) {

    /** HTTP_INBOUND runs today; the others are declared so the model does not change when their adapters arrive. */
    public enum Kind { HTTP_INBOUND, SFTP_POLLING, AS2_SERVER }

    public enum DesiredState { RUNNING, STOPPED }

    public enum RuntimeState { CONFIGURED, STARTING, RUNNING, DEGRADED, STOPPING, STOPPED, ERROR, DISABLED }

    /**
     * The state to show: a RUNNING listener whose heartbeat is older than {@code staleAfter} is DEGRADED, because
     * nothing proves it still runs.
     */
    public RuntimeState observedState(OffsetDateTime now, Duration staleAfter) {
        if (!enabled) {
            return RuntimeState.DISABLED;
        }
        if (runtimeState == RuntimeState.RUNNING
                && (lastHeartbeatAt == null || lastHeartbeatAt.plus(staleAfter).isBefore(now))) {
            return RuntimeState.DEGRADED;
        }
        return runtimeState;
    }
}
