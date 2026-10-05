package ma.mystix.referential;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import ma.mystix.shared.error.ApiError;
import ma.mystix.shared.error.ErrorCode;
import ma.mystix.shared.error.MystixException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Dated VAT referential. Rates are data (migration or operator API), never constants in code.
 * TODO(DGI-SPEC): rates come from the CGI and finance laws; each row carries its legal reference.
 */
@Service
public class VatReferential {

    private static final Set<String> CATEGORIES = Set.of("S", "Z", "E", "O");
    private static final RowMapper<VatRate> RATE = (rs, n) -> new VatRate(
            rs.getObject("id", UUID.class),
            rs.getString("country_code"),
            rs.getString("category_code"),
            rs.getBigDecimal("rate_percent"),
            rs.getObject("valid_from", LocalDate.class),
            rs.getObject("valid_to", LocalDate.class),
            rs.getString("legal_reference"),
            rs.getObject("created_at", OffsetDateTime.class));

    private final JdbcClient jdbc;
    private final Clock clock;

    VatReferential(JdbcClient jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    /** Rates of a country in force on a date, by category then rate. */
    @Transactional(readOnly = true)
    public List<VatRate> inForce(String countryCode, LocalDate date) {
        return jdbc.sql("""
                        SELECT * FROM vat_rate
                        WHERE country_code = :country AND valid_from <= :date
                          AND (valid_to IS NULL OR valid_to >= :date)
                        ORDER BY category_code, rate_percent, valid_from
                        """)
                .param("country", countryCode).param("date", date)
                .query(RATE).list();
    }

    /**
     * Standard (S) rates in force, or empty when the referential has none for that country and date:
     * an empty referential never blocks an invoice.
     */
    @Transactional(readOnly = true)
    public Optional<List<BigDecimal>> standardRates(String countryCode, LocalDate date) {
        List<BigDecimal> rates = inForce(countryCode, date).stream()
                .filter(r -> "S".equals(r.categoryCode()))
                .map(VatRate::ratePercent)
                .distinct()
                .toList();
        return rates.isEmpty() ? Optional.empty() : Optional.of(rates);
    }

    @Transactional(readOnly = true)
    public List<VatRate> all() {
        return jdbc.sql("SELECT * FROM vat_rate ORDER BY country_code, valid_from, category_code, rate_percent")
                .query(RATE).list();
    }

    @Transactional
    public VatRate add(String countryCode, String categoryCode, BigDecimal ratePercent, LocalDate validFrom,
                       LocalDate validTo, String legalReference) {
        List<ApiError.FieldViolation> violations = new ArrayList<>();
        if (countryCode == null || !countryCode.matches("^[A-Z]{2}$")) {
            violations.add(new ApiError.FieldViolation("countryCode", "must be an ISO 3166-1 alpha-2 code"));
        }
        if (categoryCode == null || !CATEGORIES.contains(categoryCode)) {
            violations.add(new ApiError.FieldViolation("categoryCode", "must be one of S, Z, E, O"));
        }
        if (ratePercent == null || ratePercent.signum() < 0 || ratePercent.compareTo(BigDecimal.valueOf(100)) > 0
                || ratePercent.scale() > 2) {
            violations.add(new ApiError.FieldViolation("ratePercent", "must be between 0 and 100, 2 decimals at most"));
        }
        if (validFrom == null) {
            violations.add(new ApiError.FieldViolation("validFrom", "must not be null"));
        } else if (validTo != null && validTo.isBefore(validFrom)) {
            violations.add(new ApiError.FieldViolation("validTo", "must not be before validFrom"));
        }
        if (legalReference == null || legalReference.isBlank()) {
            violations.add(new ApiError.FieldViolation("legalReference", "must not be blank"));
        }
        if (!violations.isEmpty()) {
            throw new MystixException(ErrorCode.VALIDATION_FAILED, "Invalid VAT rate", violations, List.of(), null);
        }
        VatRate rate = new VatRate(UUID.randomUUID(), countryCode, categoryCode, ratePercent.setScale(2),
                validFrom, validTo, legalReference.strip(), OffsetDateTime.now(clock));
        try {
            jdbc.sql("""
                            INSERT INTO vat_rate (id, country_code, category_code, rate_percent, valid_from, valid_to,
                                                  legal_reference, created_at)
                            VALUES (:id, :country, :category, :rate, :from, :to, :reference, :at)
                            """)
                    .param("id", rate.id()).param("country", countryCode).param("category", categoryCode)
                    .param("rate", rate.ratePercent()).param("from", validFrom).param("to", validTo)
                    .param("reference", rate.legalReference()).param("at", rate.createdAt())
                    .update();
        } catch (DuplicateKeyException e) {
            throw new MystixException(ErrorCode.VAT_RATE_EXISTS, "VAT rate already in the referential: " + rate, e);
        }
        return rate;
    }
}
