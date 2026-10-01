package ma.mystix.canonical;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * Computes invoice amounts following the EN 16931 calculation model: line net amounts are rounded to the
 * currency's minor unit, the VAT of each category is computed once on the summed taxable amount.
 * <p>
 * TODO(DGI-SPEC): the rounding mode required by the DGI is not published. It is a parameter, never hard-coded
 * in callers; the application configuration supplies it.
 */
public final class InvoiceCalculator {

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
    private static final Comparator<VatCategory> CATEGORY_ORDER =
            Comparator.comparing(VatCategory::code).thenComparing(VatCategory::ratePercent);

    private final RoundingMode roundingMode;

    public InvoiceCalculator(RoundingMode roundingMode) {
        this.roundingMode = Objects.requireNonNull(roundingMode, "roundingMode");
    }

    public InvoiceTotals calculate(Invoice invoice) {
        int scale = invoice.currency().getDefaultFractionDigits();

        List<BigDecimal> lineNets = new ArrayList<>();
        Map<VatCategory, BigDecimal> taxableByCategory = new TreeMap<>(CATEGORY_ORDER);
        for (InvoiceLine line : invoice.lines()) {
            BigDecimal net = line.quantity().multiply(line.unitPrice()).setScale(scale, roundingMode);
            lineNets.add(net);
            taxableByCategory.merge(line.vat(), net, BigDecimal::add);
        }

        List<InvoiceTotals.VatBreakdown> breakdown = new ArrayList<>();
        BigDecimal totalTax = BigDecimal.ZERO.setScale(scale);
        for (Map.Entry<VatCategory, BigDecimal> entry : taxableByCategory.entrySet()) {
            BigDecimal tax = entry.getValue()
                    .multiply(entry.getKey().ratePercent())
                    .divide(HUNDRED, scale, roundingMode);
            breakdown.add(new InvoiceTotals.VatBreakdown(entry.getKey(), entry.getValue(), tax));
            totalTax = totalTax.add(tax);
        }

        BigDecimal lineExtension = lineNets.stream().reduce(BigDecimal.ZERO.setScale(scale), BigDecimal::add);
        BigDecimal taxInclusive = lineExtension.add(totalTax);
        return new InvoiceTotals(lineNets, breakdown, lineExtension, lineExtension, totalTax, taxInclusive,
                taxInclusive);
    }
}
