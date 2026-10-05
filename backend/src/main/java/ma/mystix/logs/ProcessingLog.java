package ma.mystix.logs;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import ma.mystix.shared.error.ApiError;
import ma.mystix.shared.error.ErrorCode;
import ma.mystix.shared.error.MystixException;
import ma.mystix.shared.web.RequestIdFilter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

/**
 * Writes the company's processing log. Writing a log line never breaks the business flow: a failure is reported
 * in the server log and swallowed.
 */
@Service
public class ProcessingLog {

    /** Request bodies kept with a rejection, so the error can be reproduced. Larger bodies are not kept. */
    static final int MAX_PAYLOAD_BYTES = 512 * 1024;
    static final int MAX_LIMIT = 500;

    private static final Logger log = LoggerFactory.getLogger(ProcessingLog.class);

    private final LogRepository repository;
    private final Clock clock;
    private final JsonMapper json = JsonMapper.builder().build();

    ProcessingLog(LogRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    public void invoiceAccepted(UUID companyId, UUID invoiceId, String number, String message) {
        write(companyId, LogEvent.INVOICE_ACCEPTED, "STORAGE", null, number, invoiceId, message, null, null);
    }

    public void invoiceReplayed(UUID companyId, UUID invoiceId, String number) {
        write(companyId, LogEvent.INVOICE_REPLAYED, "API", null, number, invoiceId,
                "Same invoice received again: stored result returned, nothing reprocessed", null, null);
    }

    public void invoiceBackdated(UUID companyId, UUID invoiceId, String number, String message) {
        write(companyId, LogEvent.INVOICE_BACKDATED, "VALIDATION", null, number, invoiceId, message, null, null);
    }

    public void validationDecided(UUID companyId, UUID invoiceId, String number, boolean approved, String message) {
        write(companyId, approved ? LogEvent.INVOICE_VALIDATION_APPROVED : LogEvent.INVOICE_VALIDATION_REJECTED,
                "VALIDATION", null, number, invoiceId, message, null, null);
    }

    public void vatRatesUnchecked(UUID companyId, UUID invoiceId, String number, String message) {
        write(companyId, LogEvent.VAT_RATES_UNCHECKED, "VALIDATION", null, number, invoiceId, message, null, null);
    }

    public void clearance(UUID companyId, UUID invoiceId, String number, LogEvent event, String message) {
        write(companyId, event, "CLEARANCE", null, number, invoiceId, message, null, null);
    }

    /** A submission that created no invoice, with what failed and the body received. */
    public void submissionRejected(UUID companyId, String number, MystixException e, byte[] payload) {
        if (e.errorCode() == ErrorCode.COMPANY_NOT_FOUND) {
            return;
        }
        Map<String, Object> details = new LinkedHashMap<>();
        if (!e.fieldErrors().isEmpty()) {
            details.put("fieldErrors", e.fieldErrors());
        }
        if (!e.ruleViolations().isEmpty()) {
            details.put("ruleViolations", e.ruleViolations());
        }
        write(companyId, LogEvent.INVOICE_REJECTED, e.errorCode().stage().name(), e.errorCode().name(), number,
                null, summary(e), details.isEmpty() ? null : details, keepable(payload));
    }

    /** An unexpected failure during a submission (a Mystix bug). */
    public void submissionFailed(UUID companyId, String number, RuntimeException e, byte[] payload) {
        write(companyId, LogEvent.INVOICE_REJECTED, "API", ErrorCode.INTERNAL_ERROR.name(), number, null,
                "Unexpected error " + e.getClass().getSimpleName() + ": see server log with the same request id",
                null, keepable(payload));
    }

    public List<LogEntry> list(UUID companyId, LogLevel minimum, UUID invoiceId, int limit) {
        return repository.list(companyId, minimum, invoiceId, Math.clamp(limit, 1, MAX_LIMIT));
    }

    /** Aggregates for the live flow view, from {@code since} to now. */
    public List<LogStat> stats(UUID companyId, OffsetDateTime since) {
        return repository.stats(companyId, since);
    }

    public byte[] payload(UUID companyId, UUID id) {
        return repository.payload(companyId, id).orElse(null);
    }

    private static String summary(MystixException e) {
        int fields = e.fieldErrors().size();
        int rules = (int) e.ruleViolations().stream().filter(v -> "FATAL".equals(v.severity())).count();
        StringBuilder sb = new StringBuilder(e.getMessage() == null ? e.errorCode().name() : e.getMessage());
        if (fields > 0) {
            sb.append(" (").append(fields).append(fields > 1 ? " fields)" : " field)");
        }
        if (rules > 0) {
            sb.append(" (").append(rules).append(rules > 1 ? " EN 16931 rules failed)" : " EN 16931 rule failed)");
        }
        if (fields > 0) {
            ApiError.FieldViolation first = e.fieldErrors().getFirst();
            sb.append(": ").append(first.field()).append(" ").append(first.reason());
        }
        return truncate(sb.toString(), 2000);
    }

    private static byte[] keepable(byte[] payload) {
        return payload != null && payload.length <= MAX_PAYLOAD_BYTES ? payload : null;
    }

    private void write(UUID companyId, LogEvent event, String stage, String errorCode, String number,
                       UUID invoiceId, String message, Map<String, Object> details, byte[] payload) {
        try {
            if (!repository.companyExists(companyId)) {
                return;
            }
            repository.insert(companyId, OffsetDateTime.now(clock), event.level(), stage, event, errorCode,
                    truncate(number, 100), invoiceId, RequestIdFilter.current(), truncate(message, 2000),
                    details == null ? null : json.writeValueAsString(details), payload);
        } catch (RuntimeException failure) {
            log.error("Could not write processing log {} for company {}", event, companyId, failure);
        }
    }

    private static String truncate(String value, int max) {
        return value == null || value.length() <= max ? value : value.substring(0, max - 1) + "…";
    }
}
