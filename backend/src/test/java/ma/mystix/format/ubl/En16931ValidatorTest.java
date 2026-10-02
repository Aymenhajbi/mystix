package ma.mystix.format.ubl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.io.IOException;
import java.io.InputStream;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.util.List;

import ma.mystix.canonical.Invoice;
import ma.mystix.canonical.InvoiceCalculator;
import ma.mystix.canonical.TestInvoices;
import org.junit.jupiter.api.Test;

class En16931ValidatorTest {

    private static final En16931Validator VALIDATOR = new En16931Validator();

    private final UblInvoiceGenerator generator = new UblInvoiceGenerator(UblSettings.defaults());
    private final InvoiceCalculator calculator = new InvoiceCalculator(RoundingMode.HALF_UP);

    @Test
    void officialCenExampleIsCompliant() throws IOException {
        byte[] example = resource("fixtures/en16931/ubl-tc434-example1.xml");

        assertThat(fatal(VALIDATOR.validate(example))).isEmpty();
    }

    @Test
    void generatedInvoiceIsCompliant() {
        Invoice invoice = TestInvoices.basic();

        List<En16931Validator.Violation> violations =
                VALIDATOR.validate(generator.generate(invoice, calculator.calculate(invoice)));

        assertThat(fatal(violations)).isEmpty();
        assertThat(VALIDATOR.isCompliant(generator.generate(invoice, calculator.calculate(invoice)))).isTrue();
    }

    @Test
    void detectsInconsistentTotals() {
        Invoice invoice = TestInvoices.basic();
        String xml = new String(generator.generate(invoice, calculator.calculate(invoice)), StandardCharsets.UTF_8);
        // BT-112 must equal BT-109 + BT-110 (BR-CO-15).
        String broken = xml.replace(
                "<cbc:TaxInclusiveAmount currencyID=\"MAD\">1586.80</cbc:TaxInclusiveAmount>",
                "<cbc:TaxInclusiveAmount currencyID=\"MAD\">1586.81</cbc:TaxInclusiveAmount>");
        assertThat(broken).isNotEqualTo(xml);

        List<En16931Validator.Violation> violations = VALIDATOR.validate(broken.getBytes(StandardCharsets.UTF_8));

        assertThat(violations)
                .anySatisfy(v -> {
                    assertThat(v.ruleId()).isEqualTo("BR-CO-15");
                    assertThat(v.severity()).isEqualTo(En16931Validator.Severity.FATAL);
                    assertThat(v.location()).contains("Invoice");
                    assertThat(v.message()).isNotBlank();
                });
        assertThat(VALIDATOR.isCompliant(broken.getBytes(StandardCharsets.UTF_8))).isFalse();
    }

    @Test
    void rejectsDocumentsWithDoctype() {
        byte[] xxe = """
                <?xml version="1.0"?>
                <!DOCTYPE Invoice [<!ENTITY x SYSTEM "file:///C:/Windows/win.ini">]>
                <Invoice xmlns="urn:oasis:names:specification:ubl:schema:xsd:Invoice-2">&x;</Invoice>
                """.strip().getBytes(StandardCharsets.UTF_8);

        assertThatIllegalArgumentException().isThrownBy(() -> VALIDATOR.validate(xxe));
    }

    private static List<En16931Validator.Violation> fatal(List<En16931Validator.Violation> violations) {
        return violations.stream().filter(v -> v.severity() == En16931Validator.Severity.FATAL).toList();
    }

    private static byte[] resource(String path) throws IOException {
        try (InputStream in = En16931ValidatorTest.class.getClassLoader().getResourceAsStream(path)) {
            assertThat(in).as(path).isNotNull();
            return in.readAllBytes();
        }
    }
}
