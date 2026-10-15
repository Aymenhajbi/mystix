package ma.mystix.connector;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Listener supervision ({@code mystix.connectivity.*}, ADR-0016).
 *
 * @param supervisorEnabled run the supervisor in this process (heartbeats, reconciliation)
 * @param heartbeat         pause between two health checks of the listeners started here
 * @param reconcile         pause between two comparisons of the desired and the actual state
 * @param sftpStableAfter   a remote file changed more recently than this is left for the next poll (still being
 *                          written)
 */
@ConfigurationProperties("mystix.connectivity")
public record ConnectivityProperties(Boolean supervisorEnabled, Duration heartbeat, Duration reconcile,
                                     Duration sftpStableAfter) {

    public ConnectivityProperties {
        supervisorEnabled = supervisorEnabled == null || supervisorEnabled;
        heartbeat = heartbeat == null ? Duration.ofSeconds(15) : heartbeat;
        reconcile = reconcile == null ? Duration.ofSeconds(30) : reconcile;
        sftpStableAfter = sftpStableAfter == null ? Duration.ofSeconds(10) : sftpStableAfter;
    }

    /** A RUNNING listener whose heartbeat is older than this is shown DEGRADED. */
    public Duration staleAfter() {
        return heartbeat.multipliedBy(3);
    }
}
