package ma.mystix.format.ubl;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;

import ma.mystix.canonical.Invoice;
import ma.mystix.canonical.InvoiceCalculator;
import ma.mystix.canonical.TestInvoices;
import org.junit.jupiter.api.Test;

class UblInvoiceGeneratorTest {

    private static final UblSchemaValidator VALIDATOR = new UblSchemaValidator();

    private final UblInvoiceGenerator generator = new UblInvoiceGenerator(UblSettings.defaults());
    private final InvoiceCalculator calculator = new InvoiceCalculator(RoundingMode.HALF_UP);

    @Test
    void matchesExpectedFixtureByteForByte() throws IOException {
        Invoice invoice = TestInvoices.basic();

        byte[] actual = generator.generate(invoice, calculator.calculate(invoice));

        byte[] expected = fixture("fixtures/ubl/invoice-basic.expected.xml");
        assertThat(new String(actual, StandardCharsets.UTF_8)).isEqualTo(new String(expected, StandardCharsets.UTF_8));
        assertThat(actual).isEqualTo(expected);
    }

    @Test
    void outputIsValidAgainstUbl21InvoiceSchema() {
        Invoice invoice = TestInvoices.basic();

        byte[] actual = generator.generate(invoice, calculator.calculate(invoice));

        assertThat(VALIDATOR.validateInvoice(actual)).isEmpty();
    }

    @Test
    void outputIsDeterministic() {
        Invoice invoice = TestInvoices.basic();

        assertThat(generator.generate(invoice, calculator.calculate(invoice)))
                .isEqualTo(generator.generate(invoice, calculator.calculate(invoice)));
    }

    @Test
    void writesConfiguredIceScheme() {
        UblInvoiceGenerator configured = new UblInvoiceGenerator(
                new UblSettings("urn:example:cius", "ICE-TEST", "TAX"));
        Invoice invoice = TestInvoices.basic();

        String xml = new String(configured.generate(invoice, calculator.calculate(invoice)), StandardCharsets.UTF_8);

        assertThat(xml)
                .contains("<cbc:CustomizationID>urn:example:cius</cbc:CustomizationID>")
                .contains("<cbc:CompanyID schemeID=\"ICE-TEST\">000000001000011</cbc:CompanyID>");
        assertThat(VALIDATOR.validateInvoice(xml.getBytes(StandardCharsets.UTF_8))).isEmpty();
    }

    @Test
    void validatorRejectsElementsOutOfSchemaOrder() {
        Invoice invoice = TestInvoices.basic();
        String xml = new String(generator.generate(invoice, calculator.calculate(invoice)), StandardCharsets.UTF_8);
        // IssueDate must come before ID in the UBL sequence.
        String broken = xml.replace("  <cbc:ID>FA-2026-000123</cbc:ID>\n  <cbc:IssueDate>2026-09-15</cbc:IssueDate>\n",
                "  <cbc:IssueDate>2026-09-15</cbc:IssueDate>\n  <cbc:ID>FA-2026-000123</cbc:ID>\n");

        assertThat(broken).isNotEqualTo(xml);
        assertThat(VALIDATOR.validateInvoice(broken.getBytes(StandardCharsets.UTF_8))).isNotEmpty();
    }

    private static byte[] fixture(String path) throws IOException {
        try (InputStream in = UblInvoiceGeneratorTest.class.getClassLoader().getResourceAsStream(path)) {
            assertThat(in).as(path).isNotNull();
            return in.readAllBytes();
        }
    }
}
