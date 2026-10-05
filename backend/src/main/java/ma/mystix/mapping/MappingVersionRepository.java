package ma.mystix.mapping;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/** Every query is scoped by {@code company_id} and {@code flow_id}. */
@Repository
class MappingVersionRepository {

    private static final TypeReference<List<MappingRule>> RULES = new TypeReference<>() {
    };

    private final JdbcClient jdbc;
    private final JsonMapper json;
    private final RowMapper<MappingVersion> mapper;

    MappingVersionRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
        this.json = JsonMapper.builder().build();
        this.mapper = (rs, n) -> new MappingVersion(
                rs.getObject("id", UUID.class),
                rs.getObject("company_id", UUID.class),
                rs.getObject("flow_id", UUID.class),
                rs.getInt("version"),
                MappingVersion.Status.valueOf(rs.getString("status")),
                json.readValue(rs.getString("rules"), RULES),
                rs.getString("rules_sha256"),
                rs.getString("test_report") == null ? null : json.readValue(rs.getString("test_report"), TestReport.class),
                rs.getString("tested_sha256"),
                (Boolean) rs.getObject("test_passed"),
                rs.getObject("created_at", OffsetDateTime.class),
                rs.getObject("updated_at", OffsetDateTime.class),
                rs.getObject("published_at", OffsetDateTime.class));
    }

    String serialize(List<MappingRule> rules) {
        return json.writeValueAsString(rules);
    }

    List<MappingVersion> list(UUID companyId, UUID flowId) {
        return jdbc.sql("""
                SELECT id, company_id, flow_id, version, status, rules::text AS rules, rules_sha256,
                       test_report::text AS test_report, tested_sha256, test_passed, created_at, updated_at, published_at
                FROM mapping_version WHERE company_id = :companyId AND flow_id = :flowId ORDER BY version DESC
                """)
                .param("companyId", companyId)
                .param("flowId", flowId)
                .query(mapper)
                .list();
    }

    Optional<MappingVersion> find(UUID companyId, UUID flowId, int version) {
        return list(companyId, flowId).stream().filter(v -> v.version() == version).findFirst();
    }

    Optional<MappingVersion> published(UUID companyId, UUID flowId) {
        return list(companyId, flowId).stream().filter(v -> v.status() == MappingVersion.Status.PUBLISHED).findFirst();
    }

    void insert(UUID companyId, UUID flowId, int version, String rulesJson, String sha256, OffsetDateTime at) {
        jdbc.sql("""
                INSERT INTO mapping_version (id, company_id, flow_id, version, status, rules, rules_sha256,
                                             created_at, updated_at)
                VALUES (:id, :companyId, :flowId, :version, 'DRAFT', CAST(:rules AS jsonb), :sha, :at, :at)
                """)
                .param("id", UUID.randomUUID())
                .param("companyId", companyId)
                .param("flowId", flowId)
                .param("version", version)
                .param("rules", rulesJson)
                .param("sha", sha256)
                .param("at", at)
                .update();
    }

    void updateRules(UUID companyId, UUID flowId, int version, String rulesJson, String sha256, OffsetDateTime at) {
        jdbc.sql("""
                UPDATE mapping_version SET rules = CAST(:rules AS jsonb), rules_sha256 = :sha, updated_at = :at
                WHERE company_id = :companyId AND flow_id = :flowId AND version = :version AND status = 'DRAFT'
                """)
                .param("rules", rulesJson)
                .param("sha", sha256)
                .param("at", at)
                .param("companyId", companyId)
                .param("flowId", flowId)
                .param("version", version)
                .update();
    }

    void saveReport(UUID companyId, UUID flowId, int version, TestReport report) {
        jdbc.sql("""
                UPDATE mapping_version
                SET test_report = CAST(:report AS jsonb), tested_sha256 = :sha, test_passed = :passed
                WHERE company_id = :companyId AND flow_id = :flowId AND version = :version
                """)
                .param("report", json.writeValueAsString(report))
                .param("sha", report.rulesSha256())
                .param("passed", report.passed())
                .param("companyId", companyId)
                .param("flowId", flowId)
                .param("version", version)
                .update();
    }

    void setStatus(UUID companyId, UUID flowId, int version, MappingVersion.Status status, OffsetDateTime at) {
        jdbc.sql("""
                UPDATE mapping_version
                SET status = :status, updated_at = :at,
                    published_at = CASE WHEN :status = 'PUBLISHED' THEN CAST(:at AS timestamptz) ELSE published_at END
                WHERE company_id = :companyId AND flow_id = :flowId AND version = :version
                """)
                .param("status", status.name())
                .param("at", at)
                .param("companyId", companyId)
                .param("flowId", flowId)
                .param("version", version)
                .update();
    }

    int nextVersion(UUID companyId, UUID flowId) {
        return jdbc.sql("SELECT coalesce(max(version), 0) + 1 FROM mapping_version WHERE company_id = :c AND flow_id = :f")
                .param("c", companyId)
                .param("f", flowId)
                .query(Integer.class)
                .single();
    }
}
