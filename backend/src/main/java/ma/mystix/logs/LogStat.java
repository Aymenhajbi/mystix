package ma.mystix.logs;

import java.time.OffsetDateTime;

/** Number of log lines for one (event, stage, error code) since a point in time. */
public record LogStat(LogEvent event, String stage, String errorCode, long count, OffsetDateTime lastAt) {
}
