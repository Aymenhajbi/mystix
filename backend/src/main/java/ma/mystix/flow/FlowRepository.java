package ma.mystix.flow;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Every query is scoped by {@code company_id}. */
@Repository
class FlowRepository {

    private static final RowMapper<ExchangeFlow> FLOW = (rs, n) -> new ExchangeFlow(
            rs.getObject("id", UUID.class),
            rs.getObject("company_id", UUID.class),
            rs.getString("name"),
            rs.getString("document_type"),
            rs.getString("direction"),
            rs.getString("source_channel"),
            rs.getString("source_format"),
            rs.getString("target_format"),
            rs.getString("target_channel"),
            rs.getString("mapping_id"),
            rs.getString("mapping_version"),
            ExchangeFlow.Status.valueOf(rs.getString("status")),
            rs.getObject("created_at", OffsetDateTime.class),
            rs.getObject("updated_at", OffsetDateTime.class));

    private final JdbcClient jdbc;

    FlowRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    void insert(ExchangeFlow f) {
        jdbc.sql("""
                INSERT INTO exchange_flow (id, company_id, name, document_type, direction, source_channel,
                                           source_format, target_format, target_channel, mapping_id, mapping_version,
                                           status, created_at, updated_at)
                VALUES (:id, :companyId, :name, :documentType, :direction, :sourceChannel, :sourceFormat,
                        :targetFormat, :targetChannel, :mappingId, :mappingVersion, :status, :createdAt, :updatedAt)
                """)
                .param("id", f.id())
                .param("companyId", f.companyId())
                .param("name", f.name())
                .param("documentType", f.documentType())
                .param("direction", f.direction())
                .param("sourceChannel", f.sourceChannel())
                .param("sourceFormat", f.sourceFormat())
                .param("targetFormat", f.targetFormat())
                .param("targetChannel", f.targetChannel())
                .param("mappingId", f.mappingId())
                .param("mappingVersion", f.mappingVersion())
                .param("status", f.status().name())
                .param("createdAt", f.createdAt())
                .param("updatedAt", f.updatedAt())
                .update();
    }

    List<ExchangeFlow> list(UUID companyId) {
        return jdbc.sql("SELECT * FROM exchange_flow WHERE company_id = :companyId ORDER BY created_at, name")
                .param("companyId", companyId)
                .query(FLOW)
                .list();
    }

    Optional<ExchangeFlow> find(UUID companyId, UUID id) {
        return jdbc.sql("SELECT * FROM exchange_flow WHERE company_id = :companyId AND id = :id")
                .param("companyId", companyId)
                .param("id", id)
                .query(FLOW)
                .optional();
    }

    /** First active flow taking invoices through the API, oldest first. */
    Optional<ExchangeFlow> defaultApiFlow(UUID companyId) {
        return jdbc.sql("""
                SELECT * FROM exchange_flow
                WHERE company_id = :companyId AND status = 'ACTIVE'
                  AND source_channel = 'API' AND document_type = 'INVOICE'
                ORDER BY created_at, name
                LIMIT 1
                """)
                .param("companyId", companyId)
                .query(FLOW)
                .optional();
    }

    void update(UUID companyId, UUID id, String name, ExchangeFlow.Status status, OffsetDateTime at) {
        jdbc.sql("""
                UPDATE exchange_flow SET name = :name, status = :status, updated_at = :at
                WHERE company_id = :companyId AND id = :id
                """)
                .param("name", name)
                .param("status", status.name())
                .param("at", at)
                .param("companyId", companyId)
                .param("id", id)
                .update();
    }
}
