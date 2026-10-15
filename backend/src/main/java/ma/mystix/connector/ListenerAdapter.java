package ma.mystix.connector;

import java.util.List;

import ma.mystix.flow.ExchangeFlow;
import ma.mystix.shared.error.ApiError;

/**
 * Runs one kind of listener (ADR-0016). The supervisor calls it; it never decides on its own to start or stop.
 * A new protocol is a new adapter: the supervision, states, events and API stay the same.
 */
public interface ListenerAdapter {

    Listener.Kind kind();

    /** Everything that must hold before a start; empty when the listener may start. */
    List<ApiError.FieldViolation> preconditions(Context context);

    /** Opens the resource (route, port, poller). Throws when it could not be opened. */
    Handle start(Context context);

    /** Closes the resource. New messages are refused from the moment this is called. */
    void stop(Handle handle);

    /** Observed health of a started resource. */
    Health health(Handle handle);

    /**
     * False when two processes must never run the same listener (a poller that moves remote files, a bound port):
     * the supervisor then refuses to start it while another instance holds a fresh heartbeat.
     */
    default boolean multiInstance() {
        return true;
    }

    /** One acquisition cycle now, for listeners that poll; null for those that wait for requests. */
    default PollResult pollNow(Handle handle) {
        return null;
    }

    /** What one poll did. */
    record PollResult(int seen, int accepted, int rejected, int refused, int duplicates, int skipped) {
    }

    /** A start that failed for an observed, named reason ({@code code}: AUTH_FAILED, HOST_KEY_MISMATCH, …). */
    final class StartFailure extends RuntimeException {

        private final String code;

        StartFailure(String code, String detail, Throwable cause) {
            super(detail, cause);
            this.code = code;
        }

        String code() {
            return code;
        }
    }

    /** What a listener needs to run: itself, its channel, its entry point and the flow behind it. */
    record Context(Listener listener, Channel channel, EntryPoint entryPoint, ExchangeFlow flow, String apiKeyHash) {
    }

    /** A started resource, owned by the supervisor of this process. */
    record Handle(Listener.Kind kind, java.util.UUID listenerId, java.util.UUID companyId, Object resource) {
    }

    /** {@code code} is a stable diagnostic (HEALTHY, ROUTE_MISSING, …); {@code detail} explains it, without secrets. */
    record Health(boolean healthy, String code, String detail) {

        static Health ok() {
            return new Health(true, "HEALTHY", null);
        }
    }
}
