package ma.mystix.canonical;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.math.RoundingMode;

import org.junit.jupiter.api.Test;

class InvoiceCalculatorTest {

    private final InvoiceCalculator calculator = new InvoiceCalculator(RoundingMode.HALF_UP);

    @Test
    void computesLineNetsVatBreakdownAndTotals() {
        InvoiceTotals totals = calculator.calculate(TestInvoices.basic());

        assertThat(totals.lineNetAmounts()).containsExactly(
                new BigDecimal("376.50"), new BigDecimal("120.83"), new BigDecimal("900.00"));
        assertThat(totals.vatBreakdown()).containsExactly(
                new InvoiceTotals.VatBreakdown(TestInvoices.VAT_S_10, new BigDecimal("900.00"), new BigDecimal("90.00")),
                new InvoiceTotals.VatBreakdown(TestInvoices.VAT_S_20, new BigDecimal("497.33"), new BigDecimal("99.47")));
        assertThat(totals.lineExtensionAmount()).isEqualTo(new BigDecimal("1397.33"));
        assertThat(totals.taxExclusiveAmount()).isEqualTo(new BigDecimal("1397.33"));
        assertThat(totals.taxAmount()).isEqualTo(new BigDecimal("189.47"));
        assertThat(totals.taxInclusiveAmount()).isEqualTo(new BigDecimal("1586.80"));
        assertThat(totals.payableAmount()).isEqualTo(new BigDecimal("1586.80"));
    }

    @Test
    void vatIsComputedOncePerCategoryNotPerLine() {
        // Per line: 99.466 would round differently from the per-category computation on 497.33.
        InvoiceTotals totals = calculator.calculate(TestInvoices.basic());

        assertThat(totals.vatBreakdown().get(1).taxAmount()).isEqualTo(new BigDecimal("99.47"));
    }

    @Test
    void roundingModeIsAParameter() {
        InvoiceTotals down = new InvoiceCalculator(RoundingMode.DOWN).calculate(TestInvoices.basic());

        assertThat(down.lineNetAmounts().get(1)).isEqualTo(new BigDecimal("120.83"));
        assertThat(down.vatBreakdown().get(1).taxAmount()).isEqualTo(new BigDecimal("99.46"));
    }
}
