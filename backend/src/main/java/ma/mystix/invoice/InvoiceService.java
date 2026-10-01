package ma.mystix.invoice;

import java.util.List;

import ma.mystix.canonical.Invoice;
import ma.mystix.canonical.InvoiceCalculator;
import ma.mystix.format.ubl.En16931Validator;
import ma.mystix.format.ubl.UblInvoiceGenerator;
import ma.mystix.format.ubl.UblSchemaValidator;
import ma.mystix.shared.error.ApiError;
import ma.mystix.shared.error.ErrorCode;
import ma.mystix.shared.error.MystixException;
import org.springframework.stereotype.Service;

/** Canonical invoice → totals → UBL 2.1 → XSD check → EN 16931 rules. Stateless: nothing is stored yet. */
@Service
public class InvoiceService {

    private final InvoiceCalculator calculator;
    private final UblInvoiceGenerator generator;
    private final UblSchemaValidator schemaValidator;
    private final En16931Validator rulesValidator;

    InvoiceService(InvoiceCalculator calculator, UblInvoiceGenerator generator,
                   UblSchemaValidator schemaValidator, En16931Validator rulesValidator) {
        this.calculator = calculator;
        this.generator = generator;
        this.schemaValidator = schemaValidator;
        this.rulesValidator = rulesValidator;
    }

    public byte[] toUbl(Invoice invoice) {
        byte[] ubl = generator.generate(invoice, calculator.calculate(invoice));

        List<String> schemaErrors = schemaValidator.validateInvoice(ubl);
        if (!schemaErrors.isEmpty()) {
            // Our own output broke the schema: a Mystix bug, not a user error.
            throw new MystixException(ErrorCode.INVOICE_SCHEMA_INVALID,
                    "Generated UBL is not schema-valid for " + invoice.number() + ": " + schemaErrors);
        }

        List<En16931Validator.Violation> violations = rulesValidator.validate(ubl);
        if (violations.stream().anyMatch(v -> v.severity() == En16931Validator.Severity.FATAL)) {
            throw new MystixException(ErrorCode.INVOICE_RULES_VIOLATED,
                    "EN 16931 rules failed for " + invoice.number(), List.of(),
                    violations.stream()
                            .map(v -> new ApiError.RuleViolation(v.ruleId(), v.severity().name(), v.location(),
                                    v.message()))
                            .toList(),
                    null);
        }
        return ubl;
    }
}
