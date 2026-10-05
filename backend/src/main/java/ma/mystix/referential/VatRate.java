package ma.mystix.referential;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * A VAT rate of the dated referential.
 *
 * @param categoryCode   EN 16931 BT-151 code (S, Z, E, O)
 * @param validTo        last day in force, {@code null} while still in force
 * @param legalReference where the rate comes from (CGI article, finance law, circular note)
 */
public record VatRate(UUID id, String countryCode, String categoryCode, BigDecimal ratePercent, LocalDate validFrom,
                      LocalDate validTo, String legalReference, OffsetDateTime createdAt) {

    public boolean inForceOn(LocalDate date) {
        return !date.isBefore(validFrom) && (validTo == null || !date.isAfter(validTo));
    }
}
