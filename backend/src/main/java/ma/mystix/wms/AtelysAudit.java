package ma.mystix.wms;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.Year;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/** Audit trail of sensitive Atelys actions, and document numbers (REC-2026-000001...). */
@Service
public class AtelysAudit {

    static final ZoneId BUSINESS_ZONE = ZoneId.of("Africa/Casablanca");

    private final JdbcClient jdbc;
    private final Clock clock;

    AtelysAudit(JdbcClient jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    public record Entry(UUID id, String action, String objectType, UUID objectId, String actor, String reason,
                        String detail, OffsetDateTime at) {
    }

    public void record(UUID companyId, String action, String objectType, UUID objectId, String actor, String reason,
                       String detail) {
        jdbc.sql("""
                        INSERT INTO atelys_audit (id, company_id, action, object_type, object_id, actor, reason, detail,
                                                  at)
                        VALUES (:id, :c, :action, :type, :object, :actor, :reason, :detail, :at)
                        """)
                .param("id", UUID.randomUUID()).param("c", companyId).param("action", action)
                .param("type", objectType).param("object", objectId).param("actor", actor).param("reason", reason)
                .param("detail", detail).param("at", OffsetDateTime.now(clock)).update();
    }

    public List<Entry> entries(UUID companyId, UUID objectId, int limit) {
        return jdbc.sql("""
                        SELECT * FROM atelys_audit
                        WHERE company_id = :c AND (CAST(:o AS uuid) IS NULL OR object_id = :o)
                        ORDER BY at DESC LIMIT :limit
                        """)
                .param("c", companyId).param("o", objectId).param("limit", limit)
                .query((rs, n) -> new Entry(rs.getObject("id", UUID.class), rs.getString("action"),
                        rs.getString("object_type"), rs.getObject("object_id", UUID.class), rs.getString("actor"),
                        rs.getString("reason"), rs.getString("detail"), rs.getObject("at", OffsetDateTime.class)))
                .list();
    }

    /** Next number of a kind for the company, per business year: KIND-YYYY-000001. */
    public String nextNumber(UUID companyId, String kind) {
        int year = Year.now(clock.withZone(BUSINESS_ZONE)).getValue();
        long value = jdbc.sql("""
                        INSERT INTO wms_counter (company_id, kind, year, value) VALUES (:c, :k, :y, 1)
                        ON CONFLICT (company_id, kind, year) DO UPDATE SET value = wms_counter.value + 1
                        RETURNING value
                        """)
                .param("c", companyId).param("k", kind).param("y", year).query(Long.class).single();
        return "%s-%d-%06d".formatted(kind, year, value);
    }
}
