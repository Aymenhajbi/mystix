package ma.mystix.canonical;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * BG-25 Invoice line.
 *
 * @param id        BT-126 line identifier
 * @param quantity  BT-129 invoiced quantity
 * @param unitCode  BT-130 unit of measure, UN/ECE Recommendation 20 (e.g. C62, KGM)
 * @param unitPrice BT-146 item net price
 * @param itemName  BT-153 item name
 * @param gtin      BT-157 item standard identifier, GS1 GTIN (scheme 0160), optional
 * @param vat       BT-151 / BT-152
 */
public record InvoiceLine(String id, BigDecimal quantity, String unitCode, BigDecimal unitPrice, String itemName,
                          String gtin, VatCategory vat) {

    public InvoiceLine {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("Line id is required");
        }
        Objects.requireNonNull(quantity, "quantity");
        if (unitCode == null || unitCode.isBlank()) {
            throw new IllegalArgumentException("Unit code is required");
        }
        Objects.requireNonNull(unitPrice, "unitPrice");
        if (unitPrice.signum() < 0) {
            throw new IllegalArgumentException("Item net price must not be negative");
        }
        if (itemName == null || itemName.isBlank()) {
            throw new IllegalArgumentException("Item name is required");
        }
        if (gtin != null && !Gs1.isValidGtin(gtin)) {
            throw new IllegalArgumentException("GTIN has an invalid length or check digit");
        }
        Objects.requireNonNull(vat, "vat");
    }
}
