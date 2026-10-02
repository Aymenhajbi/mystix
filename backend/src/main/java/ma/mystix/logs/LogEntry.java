package ma.mystix.logs;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * One line of a company's processing log.
 *
 * @param details    JSON text with field errors and rule violations, or {@code null}
 * @param payloadSize size in bytes of the stored request payload, {@code null} when none is kept
 */
public record LogEntry(UUID id, long seq, UUID companyId, OffsetDateTime occurredAt, LogLevel level, String stage,
                       LogEvent event, String errorCode, String invoiceNumber, UUID invoiceId, String requestId,
                       String message, String details, Integer payloadSize) {
}
