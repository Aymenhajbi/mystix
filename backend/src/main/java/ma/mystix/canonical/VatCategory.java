package ma.mystix.canonical;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * BT-151 VAT category code and BT-152 VAT rate (percent).
 * The rate always comes from the dated VAT referential or the source document, never from a constant.
 */
public record VatCategory(VatCategoryCode code, BigDecimal ratePercent) {

    public VatCategory {
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(ratePercent, "ratePercent");
        if (ratePercent.signum() < 0 || ratePercent.compareTo(BigDecimal.valueOf(100)) > 0) {
            throw new IllegalArgumentException("VAT rate must be between 0 and 100");
        }
        ratePercent = ratePercent.stripTrailingZeros();
    }
}
