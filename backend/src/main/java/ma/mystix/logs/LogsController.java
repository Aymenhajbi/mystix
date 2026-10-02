package ma.mystix.logs;

import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import ma.mystix.shared.error.ErrorCode;
import ma.mystix.shared.error.LocalizedText;
import ma.mystix.shared.error.MystixException;
import ma.mystix.tenant.CompanyService;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Processing log of the company, newest first.
 * TODO(auth): company from the authenticated principal instead of the header (Lot 8).
 */
@RestController
@RequestMapping("/api/v1/logs")
class LogsController {

    private final ProcessingLog logs;
    private final CompanyService companies;
    private final JsonMapper json;
    private final Clock clock;

    LogsController(ProcessingLog logs, CompanyService companies, JsonMapper json, Clock clock) {
        this.logs = logs;
        this.companies = companies;
        this.json = json;
        this.clock = clock;
    }

    /**
     * @param userMessage French/Arabic explanation of the error code, {@code null} for non-error events
     * @param details     field errors and rule violations, {@code null} when none
     */
    record LogView(UUID id, long seq, OffsetDateTime occurredAt, LogLevel level, String stage, LogEvent event,
                   String errorCode, LocalizedText userMessage, LocalizedText suggestedAction, String invoiceNumber,
                   UUID invoiceId, String requestId, String message, JsonNode details, Integer payloadSize) {
    }

    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    List<LogView> list(@RequestHeader("X-Mystix-Company-Id") UUID companyId,
                       @RequestParam(defaultValue = "INFO") LogLevel level,
                       @RequestParam(required = false) UUID invoiceId,
                       @RequestParam(defaultValue = "200") int limit) {
        companies.get(companyId);
        return logs.list(companyId, level, invoiceId, limit).stream().map(this::view).toList();
    }

    private static final Duration MAX_WINDOW = Duration.ofDays(31);

    record StatsView(OffsetDateTime since, OffsetDateTime until, List<LogStat> stats) {
    }

    /**
     * Counts per event, stage and error code over the last {@code window} (ISO 8601 duration, default PT24H).
     * Feeds the live flow view; every number comes from the processing log.
     */
    @GetMapping(value = "/stats", produces = MediaType.APPLICATION_JSON_VALUE)
    StatsView stats(@RequestHeader("X-Mystix-Company-Id") UUID companyId,
                    @RequestParam(defaultValue = "PT24H") String window) {
        companies.get(companyId);
        Duration duration;
        try {
            duration = Duration.parse(window);
        } catch (java.time.format.DateTimeParseException e) {
            throw new MystixException(ErrorCode.VALIDATION_FAILED, "window must be an ISO 8601 duration", e);
        }
        if (duration.isNegative() || duration.isZero()) {
            throw new MystixException(ErrorCode.VALIDATION_FAILED, "window must be positive");
        }
        Duration capped = duration.compareTo(MAX_WINDOW) > 0 ? MAX_WINDOW : duration;
        OffsetDateTime until = OffsetDateTime.now(clock);
        OffsetDateTime since = until.minus(capped);
        return new StatsView(since, until, logs.stats(companyId, since));
    }

    @GetMapping("/{id}/payload")
    ResponseEntity<byte[]> payload(@RequestHeader("X-Mystix-Company-Id") UUID companyId, @PathVariable UUID id) {
        byte[] payload = logs.payload(companyId, id);
        if (payload == null) {
            throw new MystixException(ErrorCode.RESOURCE_NOT_FOUND, "No payload for log " + id);
        }
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(payload);
    }

    private LogView view(LogEntry e) {
        ErrorCode code = errorCode(e.errorCode());
        return new LogView(e.id(), e.seq(), e.occurredAt(), e.level(), e.stage(), e.event(), e.errorCode(),
                code == null ? null : code.userMessage(), code == null ? null : code.suggestedAction(),
                e.invoiceNumber(), e.invoiceId(), e.requestId(), e.message(),
                e.details() == null ? null : json.readTree(e.details()), e.payloadSize());
    }

    private static ErrorCode errorCode(String name) {
        if (name == null) {
            return null;
        }
        try {
            return ErrorCode.valueOf(name);
        } catch (IllegalArgumentException unknown) {
            return null;
        }
    }
}
