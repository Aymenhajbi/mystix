package ma.mystix.partner;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import ma.mystix.canonical.Gs1;
import ma.mystix.shared.error.ApiError;
import ma.mystix.shared.error.ErrorCode;
import ma.mystix.shared.error.MystixException;
import ma.mystix.tenant.CompanyService;
import ma.mystix.tenant.Ice;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Partners of each client environment. Every query is scoped by {@code company_id}. */
@Service
public class PartnerService {

    private static final RowMapper<Partner> PARTNER = (rs, n) -> new Partner(
            rs.getObject("id", UUID.class),
            rs.getObject("company_id", UUID.class),
            rs.getString("name"),
            Partner.Type.valueOf(rs.getString("type")),
            rs.getString("ice"),
            rs.getString("gln"),
            rs.getString("reference"),
            rs.getObject("created_at", OffsetDateTime.class));

    private final JdbcClient jdbc;
    private final CompanyService companies;
    private final Clock clock;

    PartnerService(JdbcClient jdbc, CompanyService companies, Clock clock) {
        this.jdbc = jdbc;
        this.companies = companies;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<Partner> list(UUID companyId) {
        companies.get(companyId);
        return jdbc.sql("SELECT * FROM partner WHERE company_id = :c ORDER BY type, name")
                .param("c", companyId).query(PARTNER).list();
    }

    @Transactional(readOnly = true)
    public Partner get(UUID companyId, UUID partnerId) {
        return jdbc.sql("SELECT * FROM partner WHERE company_id = :c AND id = :id")
                .param("c", companyId).param("id", partnerId).query(PARTNER).optional()
                .orElseThrow(() -> new MystixException(ErrorCode.PARTNER_NOT_FOUND,
                        "No partner " + partnerId + " for company " + companyId));
    }

    @Transactional
    public Partner create(UUID companyId, String name, Partner.Type type, String ice, String gln, String reference) {
        companies.get(companyId);
        String cleanIce = blankToNull(ice);
        String cleanGln = blankToNull(gln);
        if (cleanIce != null && !Ice.isValid(cleanIce)) {
            throw invalid("ice", "must be exactly 15 digits");
        }
        if (cleanGln != null && !Gs1.isValidGln(cleanGln)) {
            throw invalid("gln", "must be 13 digits with a valid GS1 check digit");
        }
        Partner partner = new Partner(UUID.randomUUID(), companyId, name.strip(), type, cleanIce, cleanGln,
                blankToNull(reference), OffsetDateTime.now(clock));
        try {
            jdbc.sql("""
                    INSERT INTO partner (id, company_id, name, type, ice, gln, reference, created_at)
                    VALUES (:id, :c, :name, :type, :ice, :gln, :reference, :at)
                    """)
                    .param("id", partner.id()).param("c", companyId).param("name", partner.name())
                    .param("type", type.name()).param("ice", cleanIce).param("gln", cleanGln)
                    .param("reference", partner.reference()).param("at", partner.createdAt())
                    .update();
        } catch (DuplicateKeyException e) {
            throw new MystixException(ErrorCode.PARTNER_NAME_TAKEN, "Partner name taken: " + partner.name(), e);
        }
        return partner;
    }

    private static MystixException invalid(String field, String reason) {
        return new MystixException(ErrorCode.VALIDATION_FAILED, "Invalid partner " + field,
                List.of(new ApiError.FieldViolation(field, reason)), List.of(), null);
    }

    private static String blankToNull(String v) {
        return v == null || v.isBlank() ? null : v.strip();
    }
}
