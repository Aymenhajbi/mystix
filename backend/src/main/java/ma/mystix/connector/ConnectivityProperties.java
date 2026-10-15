package ma.mystix.connector;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Listener supervision ({@code mystix.connectivity.*}, ADR-0016).
 *
 * @param supervisorEnabled run the supervisor in this process (heartbeats, reconciliation)
 * @param heartbeat         pause between two health checks of the listeners started here
 * @param reconcile         pause between two comparisons of the desired and the actual state
 */
@ConfigurationProperties("mystix.connectivity")
public record ConnectivityProperties(Boolean supervisorEnabled, Duration heartbeat, Duration reconcile) {

    public ConnectivityProperties {
        supervisorEnabled = supervisorEnabled == null || supervisorEnabled;
        heartbeat = heartbeat == null ? Duration.ofSeconds(15) : heartbeat;
        reconcile = reconcile == null ? Duration.ofSeconds(30) : reconcile;
    }

    /** A RUNNING listener whose heartbeat is older than this is shown DEGRADED. */
    public Duration staleAfter() {
        return heartbeat.multipliedBy(3);
    }
}
