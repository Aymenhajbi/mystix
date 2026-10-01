package ma.mystix.canonical;

import java.math.BigDecimal;
import java.util.List;

/**
 * Amounts derived from the invoice lines.
 *
 * @param lineNetAmounts      BT-131 line net amount, in line order
 * @param vatBreakdown        BG-23, one entry per VAT category and rate, ordered by category then rate
 * @param lineExtensionAmount BT-106 sum of invoice line net amounts
 * @param taxExclusiveAmount  BT-109 invoice total amount without VAT
 * @param taxAmount           BT-110 invoice total VAT amount
 * @param taxInclusiveAmount  BT-112 invoice total amount with VAT
 * @param payableAmount       BT-115 amount due for payment
 */
public record InvoiceTotals(List<BigDecimal> lineNetAmounts, List<VatBreakdown> vatBreakdown,
                            BigDecimal lineExtensionAmount, BigDecimal taxExclusiveAmount, BigDecimal taxAmount,
                            BigDecimal taxInclusiveAmount, BigDecimal payableAmount) {

    public InvoiceTotals {
        lineNetAmounts = List.copyOf(lineNetAmounts);
        vatBreakdown = List.copyOf(vatBreakdown);
    }

    /**
     * BG-23 VAT breakdown.
     *
     * @param category      BT-118 / BT-119
     * @param taxableAmount BT-116 VAT category taxable amount
     * @param taxAmount     BT-117 VAT category tax amount
     */
    public record VatBreakdown(VatCategory category, BigDecimal taxableAmount, BigDecimal taxAmount) {
    }
}
