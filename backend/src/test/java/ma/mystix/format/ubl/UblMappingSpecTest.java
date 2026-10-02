package ma.mystix.format.ubl;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.RoundingMode;

import ma.mystix.canonical.Invoice;
import ma.mystix.canonical.InvoiceCalculator;
import ma.mystix.canonical.TestInvoices;
import org.junit.jupiter.api.Test;

/** Guards the mapping description against drift: every target it names exists in the generator's real output. */
class UblMappingSpecTest {

    private final Invoice invoice = TestInvoices.basic();
    private final UblXPath out = new UblXPath(new UblInvoiceGenerator(UblSettings.defaults())
            .generate(invoice, new InvoiceCalculator(RoundingMode.HALF_UP).calculate(invoice)));

    @Test
    void everyTargetExistsInTheGeneratedUbl() {
        assertThat(UblMappingSpec.FIELDS)
                .filteredOn(f -> f.target() != null)
                .allSatisfy(f -> assertThat(out.text(f.target().replace("{n}", "1")))
                        .as("%s %s → %s", f.term(), f.label(), f.target())
                        .isNotNull());
    }

    @Test
    void notEmittedFieldsHaveNoTargetAndEmittedOnesDo() {
        assertThat(UblMappingSpec.FIELDS).allSatisfy(f -> {
            if (f.kind() == UblMappingSpec.Kind.NOT_EMITTED) {
                assertThat(f.target()).isNull();
            } else {
                assertThat(f.target()).isNotNull();
            }
        });
    }

    @Test
    void readsReferenceValues() {
        assertThat(out.text("/inv:Invoice/cbc:ID")).isEqualTo("FA-2026-000123");
        assertThat(out.text("/inv:Invoice/cac:InvoiceLine[2]/cbc:LineExtensionAmount")).isEqualTo("120.83");
        assertThat(out.text("/inv:Invoice/cac:InvoiceLine[1]/cbc:InvoicedQuantity/@unitCode")).isEqualTo("C62");
        assertThat(out.text("/inv:Invoice/cbc:Missing")).isNull();
    }
}
