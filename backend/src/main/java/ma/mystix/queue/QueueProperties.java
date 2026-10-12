package ma.mystix.queue;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Work queue settings ({@code mystix.queue.*}, ADR-0003).
 *
 * @param workerEnabled     poll and run jobs in this process; off where another node does it, or in tests
 * @param pollInterval      pause between two polls when the previous one found nothing
 * @param concurrency       jobs run at the same time by this process (virtual threads)
 * @param maxLanes          lanes looked at per poll; each gets its share of the free slots
 * @param defaultMaxRunning jobs of one lane running at the same time, across all nodes, unless queue_lane says
 *                          otherwise
 * @param maxAttempts       attempts before a retryable failure goes to DEAD
 * @param lease             how long a claimed job stays reserved; past it, the job is released for another worker
 * @param backoffBase       delay before the second attempt; doubles at each attempt
 * @param backoffMax        upper bound of that delay
 */
@ConfigurationProperties("mystix.queue")
public record QueueProperties(Boolean workerEnabled, Duration pollInterval, Integer concurrency, Integer maxLanes,
                              Integer defaultMaxRunning, Integer maxAttempts, Duration lease, Duration backoffBase,
                              Duration backoffMax) {

    public QueueProperties {
        workerEnabled = workerEnabled == null || workerEnabled;
        pollInterval = pollInterval == null ? Duration.ofMillis(500) : pollInterval;
        concurrency = concurrency == null ? 16 : concurrency;
        maxLanes = maxLanes == null ? 50 : maxLanes;
        defaultMaxRunning = defaultMaxRunning == null ? 4 : defaultMaxRunning;
        maxAttempts = maxAttempts == null ? 8 : maxAttempts;
        lease = lease == null ? Duration.ofMinutes(5) : lease;
        backoffBase = backoffBase == null ? Duration.ofSeconds(2) : backoffBase;
        backoffMax = backoffMax == null ? Duration.ofMinutes(15) : backoffMax;
    }
}
