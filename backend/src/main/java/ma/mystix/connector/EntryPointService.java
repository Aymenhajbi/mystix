package ma.mystix.connector;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

import ma.mystix.flow.FlowService;
import ma.mystix.partner.PartnerService;
import ma.mystix.shared.error.ApiError;
import ma.mystix.shared.error.ErrorCode;
import ma.mystix.shared.error.MystixException;
import ma.mystix.tenant.CompanyService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Entry points of each client environment (ADR-0016): the door of the pipeline a listener hands its messages to.
 * Every query is scoped by {@code company_id}.
 */
@Service
public class EntryPointService {

    private static final Logger LOG = LoggerFactory.getLogger(EntryPointService.class);
    private static final Pattern CODE = Pattern.compile("^[A-Z0-9][A-Z0-9_-]{0,59}$");

    public record Config(String code, String name, UUID flowId, EntryPoint.DocumentType documentType,
                         EntryPoint.InputFormat inputFormat, UUID partnerId, Boolean enabled) {
    }

    private final JdbcClient jdbc;
    private final CompanyService companies;
    private final FlowService flows;
    private final PartnerService partners;
    private final Clock clock;
    private final RowMapper<EntryPoint> rows = (rs, n) -> new EntryPoint(
            rs.getObject("id", UUID.class),
            rs.getObject("company_id", UUID.class),
            rs.getString("code"),
            rs.getString("name"),
            rs.getObject("flow_id", UUID.class),
            EntryPoint.DocumentType.valueOf(rs.getString("document_type")),
            EntryPoint.InputFormat.valueOf(rs.getString("input_format")),
            rs.getObject("partner_id", UUID.class),
            rs.getBoolean("enabled"),
            rs.getString("created_by"),
            rs.getObject("created_at", OffsetDateTime.class),
            rs.getObject("updated_at", OffsetDateTime.class));

    EntryPointService(JdbcClient jdbc, CompanyService companies, FlowService flows, PartnerService partners,
                      Clock clock) {
        this.jdbc = jdbc;
        this.companies = companies;
        this.flows = flows;
        this.partners = partners;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<EntryPoint> list(UUID companyId) {
        companies.get(companyId);
        return jdbc.sql("SELECT * FROM entry_point WHERE company_id = :c ORDER BY code")
                .param("c", companyId).query(rows).list();
    }

    @Transactional(readOnly = true)
    public EntryPoint get(UUID companyId, UUID id) {
        return jdbc.sql("SELECT * FROM entry_point WHERE company_id = :c AND id = :id")
                .param("c", companyId).param("id", id).query(rows).optional()
                .orElseThrow(() -> new MystixException(ErrorCode.ENTRY_POINT_NOT_FOUND,
                        "No entry point " + id + " for company " + companyId));
    }

    @Transactional
    public EntryPoint create(UUID companyId, Config config, String actor) {
        companies.get(companyId);
        check(companyId, config);
        UUID id = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now(clock);
        try {
            jdbc.sql("""
                            INSERT INTO entry_point (id, company_id, code, name, flow_id, document_type, input_format,
                                                     partner_id, enabled, created_by, created_at, updated_at)
                            VALUES (:id, :c, :code, :name, :flow, :doc, :format, :partner, :enabled, :actor, :now,
                                    :now)
                            """)
                    .param("id", id).param("c", companyId).param("code", config.code()).param("name", config.name().strip())
                    .param("flow", config.flowId()).param("doc", config.documentType().name())
                    .param("format", config.inputFormat().name()).param("partner", config.partnerId())
                    .param("enabled", config.enabled() == null || config.enabled()).param("actor", actor)
                    .param("now", now).update();
        } catch (DuplicateKeyException e) {
            throw new MystixException(ErrorCode.ENTRY_POINT_CODE_TAKEN, "Entry point code taken: " + config.code(), e);
        }
        LOG.info("Entry point {} ({}) created for company {} by {}", id, config.code(), companyId, actor);
        return get(companyId, id);
    }

    @Transactional
    public EntryPoint update(UUID companyId, UUID id, Config config, String actor) {
        get(companyId, id);
        check(companyId, config);
        try {
            jdbc.sql("""
                            UPDATE entry_point SET code = :code, name = :name, flow_id = :flow, document_type = :doc,
                                   input_format = :format, partner_id = :partner, enabled = :enabled,
                                   updated_at = :now
                            WHERE company_id = :c AND id = :id
                            """)
                    .param("code", config.code()).param("name", config.name().strip()).param("flow", config.flowId())
                    .param("doc", config.documentType().name()).param("format", config.inputFormat().name())
                    .param("partner", config.partnerId()).param("enabled", config.enabled() == null || config.enabled())
                    .param("now", OffsetDateTime.now(clock)).param("c", companyId).param("id", id).update();
        } catch (DuplicateKeyException e) {
            throw new MystixException(ErrorCode.ENTRY_POINT_CODE_TAKEN, "Entry point code taken: " + config.code(), e);
        }
        LOG.info("Entry point {} updated for company {} by {}", id, companyId, actor);
        return get(companyId, id);
    }

    private void check(UUID companyId, Config c) {
        List<ApiError.FieldViolation> violations = new ArrayList<>();
        if (c.code() == null || !CODE.matcher(c.code()).matches()) {
            violations.add(new ApiError.FieldViolation("code", "1 to 60 characters: A-Z, 0-9, _ and -"));
        }
        if (c.name() == null || c.name().isBlank() || c.name().strip().length() > 120) {
            violations.add(new ApiError.FieldViolation("name", "required, at most 120 characters"));
        }
        if (c.documentType() == null) {
            violations.add(new ApiError.FieldViolation("documentType", "required"));
        }
        if (c.inputFormat() == null) {
            violations.add(new ApiError.FieldViolation("inputFormat", "required"));
        }
        if (c.flowId() == null) {
            violations.add(new ApiError.FieldViolation("flowId", "required"));
        }
        if (!violations.isEmpty()) {
            throw new MystixException(ErrorCode.VALIDATION_FAILED, "Invalid entry point", violations, List.of(), null);
        }
        flows.get(companyId, c.flowId());
        if (c.partnerId() != null) {
            partners.get(companyId, c.partnerId());
        }
    }
}
