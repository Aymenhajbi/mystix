package ma.mystix.admin;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Operator view across client environments (cross-tenant, read-only).
 * Off unless {@code mystix.admin.enabled=true}. TODO(auth): restrict to the operator role once authentication
 * exists (Lot 8); until then it must stay disabled anywhere but a developer workstation.
 */
@RestController
@RequestMapping("/api/v1/admin")
@ConditionalOnProperty(name = "mystix.admin.enabled", havingValue = "true")
class AdminController {

    private final JdbcClient jdbc;

    AdminController(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    record Environment(UUID id, String ice, String legalName, OffsetDateTime createdAt, long flows,
                       long activeFlows, long invoices, long errors24h, OffsetDateTime lastActivity) {
    }

    @GetMapping(value = "/environments", produces = MediaType.APPLICATION_JSON_VALUE)
    List<Environment> environments() {
        return jdbc.sql("""
                SELECT c.id, c.ice, c.legal_name, c.created_at,
                       (SELECT count(*) FROM exchange_flow f WHERE f.company_id = c.id) AS flows,
                       (SELECT count(*) FROM exchange_flow f WHERE f.company_id = c.id AND f.status = 'ACTIVE')
                           AS active_flows,
                       (SELECT count(*) FROM invoice_message m WHERE m.company_id = c.id) AS invoices,
                       (SELECT count(*) FROM processing_log l WHERE l.company_id = c.id AND l.level = 'ERROR'
                           AND l.occurred_at >= now() - interval '24 hours') AS errors_24h,
                       (SELECT max(l.occurred_at) FROM processing_log l WHERE l.company_id = c.id) AS last_activity
                FROM company c
                ORDER BY c.legal_name, c.ice
                """)
                .query((rs, n) -> new Environment(
                        rs.getObject("id", UUID.class),
                        rs.getString("ice"),
                        rs.getString("legal_name"),
                        rs.getObject("created_at", OffsetDateTime.class),
                        rs.getLong("flows"),
                        rs.getLong("active_flows"),
                        rs.getLong("invoices"),
                        rs.getLong("errors_24h"),
                        rs.getObject("last_activity", OffsetDateTime.class)))
                .list();
    }
}
