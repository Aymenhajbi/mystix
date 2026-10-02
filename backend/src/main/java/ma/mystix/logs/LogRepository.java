package ma.mystix.logs;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Append-only. Every query is scoped by {@code company_id}. */
@Repository
class LogRepository {

    private static final String COLUMNS = """
            id, seq, company_id, occurred_at, level, stage, event, error_code, invoice_number, invoice_id,
            request_id, message, details::text AS details, octet_length(payload) AS payload_size
            """;

    private static final RowMapper<LogEntry> ENTRY = (rs, n) -> new LogEntry(
            rs.getObject("id", UUID.class),
            rs.getLong("seq"),
            rs.getObject("company_id", UUID.class),
            rs.getObject("occurred_at", OffsetDateTime.class),
            LogLevel.valueOf(rs.getString("level")),
            rs.getString("stage"),
            LogEvent.valueOf(rs.getString("event")),
            rs.getString("error_code"),
            rs.getString("invoice_number"),
            rs.getObject("invoice_id", UUID.class),
            rs.getString("request_id"),
            rs.getString("message"),
            rs.getString("details"),
            (Integer) rs.getObject("payload_size"));

    private final JdbcClient jdbc;

    LogRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    boolean companyExists(UUID companyId) {
        return jdbc.sql("SELECT count(*) FROM company WHERE id = :id").param("id", companyId)
                .query(Long.class).single() > 0;
    }

    void insert(UUID companyId, OffsetDateTime at, LogLevel level, String stage, LogEvent event, String errorCode,
                String invoiceNumber, UUID invoiceId, String requestId, String message, String detailsJson,
                byte[] payload) {
        jdbc.sql("""
                INSERT INTO processing_log (id, company_id, occurred_at, level, stage, event, error_code,
                                            invoice_number, invoice_id, request_id, message, details, payload)
                VALUES (:id, :companyId, :at, :level, :stage, :event, :errorCode, :invoiceNumber, :invoiceId,
                        :requestId, :message, CAST(:details AS jsonb), :payload)
                """)
                .param("id", UUID.randomUUID())
                .param("companyId", companyId)
                .param("at", at)
                .param("level", level.name())
                .param("stage", stage)
                .param("event", event.name())
                .param("errorCode", errorCode)
                .param("invoiceNumber", invoiceNumber)
                .param("invoiceId", invoiceId)
                .param("requestId", requestId)
                .param("message", message)
                .param("details", detailsJson)
                .param("payload", payload)
                .update();
    }

    List<LogEntry> list(UUID companyId, LogLevel minimum, UUID invoiceId, int limit) {
        return jdbc.sql("SELECT " + COLUMNS + """
                FROM processing_log
                WHERE company_id = :companyId
                  AND (CAST(:invoiceId AS uuid) IS NULL OR invoice_id = :invoiceId)
                  AND level IN (:levels)
                ORDER BY seq DESC
                LIMIT :limit
                """)
                .param("companyId", companyId)
                .param("invoiceId", invoiceId)
                .param("levels", levelsFrom(minimum))
                .param("limit", limit)
                .query(ENTRY)
                .list();
    }

    /** Counts per (event, stage, error code) since a point in time, with the latest occurrence of each. */
    List<LogStat> stats(UUID companyId, OffsetDateTime since) {
        return jdbc.sql("""
                SELECT event, stage, error_code, count(*) AS n, max(occurred_at) AS last_at
                FROM processing_log
                WHERE company_id = :companyId AND occurred_at >= :since
                GROUP BY event, stage, error_code
                ORDER BY event, stage, error_code
                """)
                .param("companyId", companyId)
                .param("since", since)
                .query((rs, n) -> new LogStat(LogEvent.valueOf(rs.getString("event")), rs.getString("stage"),
                        rs.getString("error_code"), rs.getLong("n"), rs.getObject("last_at", OffsetDateTime.class)))
                .list();
    }

    Optional<byte[]> payload(UUID companyId, UUID id) {
        return jdbc.sql("SELECT payload FROM processing_log WHERE company_id = :companyId AND id = :id")
                .param("companyId", companyId)
                .param("id", id)
                .query((rs, n) -> rs.getBytes("payload"))
                .optional();
    }

    private static List<String> levelsFrom(LogLevel minimum) {
        return switch (minimum) {
            case INFO -> List.of("INFO", "WARN", "ERROR");
            case WARN -> List.of("WARN", "ERROR");
            case ERROR -> List.of("ERROR");
        };
    }
}
