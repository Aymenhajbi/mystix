package ma.mystix.invoice;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import ma.mystix.canonical.CanonicalVersion;
import ma.mystix.clearance.ClearanceGateway;
import ma.mystix.clearance.ClearanceRequest;
import ma.mystix.clearance.ClearanceResult;
import ma.mystix.canonical.Invoice;
import ma.mystix.canonical.InvoiceCalculator;
import ma.mystix.canonical.InvoiceTotals;
import ma.mystix.format.ubl.En16931Validator;
import ma.mystix.format.ubl.UblInvoiceGenerator;
import ma.mystix.format.ubl.UblSchemaValidator;
import ma.mystix.shared.Sha256;
import ma.mystix.shared.error.ApiError;
import ma.mystix.shared.error.ErrorCode;
import ma.mystix.shared.error.MystixException;
import ma.mystix.tenant.Company;
import ma.mystix.tenant.CompanyService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

/**
 * Accepts invoices for a company: canonical → totals → UBL 2.1 → XSD → EN 16931 → storage with artefacts.
 * Idempotent on (company, invoice number): the same canonical content returns the stored UBL, different content
 * under the same number is a conflict. Clearance goes through {@link ClearanceGateway}, simulated only (ADR-0001):
 * nothing is sent to the DGI.
 */
@Service
public class InvoiceService {

    private static final Logger log = LoggerFactory.getLogger(InvoiceService.class);
    static final int MAX_PAGE_SIZE = 200;

    private final CompanyService companies;
    private final InvoiceRepository repository;
    private final InvoiceCalculator calculator;
    private final UblInvoiceGenerator generator;
    private final UblSchemaValidator schemaValidator;
    private final En16931Validator rulesValidator;
    private final ClearanceGateway clearance;
    private final Clock clock;

    InvoiceService(CompanyService companies, InvoiceRepository repository, InvoiceCalculator calculator,
                   UblInvoiceGenerator generator, UblSchemaValidator schemaValidator,
                   En16931Validator rulesValidator, ClearanceGateway clearance, Clock clock) {
        this.clearance = clearance;
        this.companies = companies;
        this.repository = repository;
        this.calculator = calculator;
        this.generator = generator;
        this.schemaValidator = schemaValidator;
        this.rulesValidator = rulesValidator;
        this.clock = clock;
    }

    /** Result of a submission; {@code replayed} is true when the invoice had already been accepted. */
    public record Submission(StoredInvoice invoice, byte[] ubl, boolean replayed) {
    }

    public Submission submit(UUID companyId, byte[] rawRequest, Invoice invoice) {
        Company company = companies.get(companyId);
        if (invoice.seller().ice() != null && !invoice.seller().ice().equals(company.ice().value())) {
            throw new MystixException(ErrorCode.INVOICE_REJECTED, "Seller ICE differs from company " + companyId,
                    List.of(new ApiError.FieldViolation("seller.ice",
                            "must match the ICE of the company submitting the invoice")),
                    List.of(), null);
        }

        byte[] canonical = CanonicalJson.write(invoice);
        String canonicalSha256 = Sha256.hex(canonical);

        var existing = repository.findByNumber(companyId, invoice.number());
        if (existing.isPresent()) {
            return replay(existing.get(), canonicalSha256);
        }

        InvoiceTotals totals = calculator.calculate(invoice);
        byte[] ubl = toUbl(invoice, totals);
        StoredInvoice stored = new StoredInvoice(UUID.randomUUID(), companyId, invoice.number(), invoice.issueDate(),
                StoredInvoice.STATUS_VALIDATED, CanonicalVersion.CURRENT, canonicalSha256, OffsetDateTime.now(clock),
                null, null, null, invoice.buyer().name(), invoice.currency().getCurrencyCode(),
                totals.payableAmount());
        Map<ArtifactKind, byte[]> artifacts = new EnumMap<>(ArtifactKind.class);
        artifacts.put(ArtifactKind.RAW, rawRequest);
        artifacts.put(ArtifactKind.CANONICAL, canonical);
        artifacts.put(ArtifactKind.OUT, ubl);
        try {
            repository.insert(stored, artifacts);
        } catch (DuplicateKeyException e) {
            // A concurrent submission with the same number won the race.
            return replay(repository.findByNumber(companyId, invoice.number()).orElseThrow(() -> e),
                    canonicalSha256);
        }
        return new Submission(clear(stored, ubl), ubl, false);
    }

    /** Most recent invoices of the company first. */
    public List<StoredInvoice> list(UUID companyId, int limit) {
        companies.get(companyId);
        return repository.list(companyId, Math.clamp(limit, 1, MAX_PAGE_SIZE));
    }

    public List<StoredInvoice.StatusEvent> history(UUID companyId, UUID invoiceId) {
        return repository.history(companyId, get(companyId, invoiceId).id());
    }

    /**
     * Submits a stored invoice for clearance, synchronously for now (the Lot 4 queue will retry failures).
     * A gateway failure leaves the invoice VALIDATED and is recorded in its history.
     */
    private StoredInvoice clear(StoredInvoice stored, byte[] ubl) {
        ClearanceResult result;
        try {
            result = clearance.submit(new ClearanceRequest(stored.companyId(), stored.id(), stored.number(), ubl,
                    Sha256.hex(ubl)));
        } catch (RuntimeException e) {
            log.warn("Clearance failed for invoice {}", stored.id(), e);
            repository.appendEvent(stored.companyId(), stored.id(), StoredInvoice.EVENT_CLEARANCE_ERROR,
                    "Clearance gateway error; invoice stays VALIDATED", OffsetDateTime.now(clock));
            return stored;
        }
        boolean cleared = result.outcome() == ClearanceResult.Outcome.CLEARED;
        String detail = (result.simulated() ? "SIMULATED clearance: " : "")
                + (cleared ? "cleared" : result.reason());
        repository.recordClearance(stored.companyId(), stored.id(),
                cleared ? StoredInvoice.STATUS_CLEARED : StoredInvoice.STATUS_CLEARANCE_REJECTED,
                result.reference(), result.simulated(), result.at(), detail);
        return get(stored.companyId(), stored.id());
    }

    public StoredInvoice get(UUID companyId, UUID invoiceId) {
        return repository.findById(companyId, invoiceId)
                .orElseThrow(() -> new MystixException(ErrorCode.INVOICE_NOT_FOUND,
                        "No invoice " + invoiceId + " for company " + companyId));
    }

    public List<StoredInvoice.ArtifactInfo> artifacts(UUID companyId, UUID invoiceId) {
        return repository.artifacts(companyId, get(companyId, invoiceId).id());
    }

    public byte[] artifact(UUID companyId, UUID invoiceId, ArtifactKind kind) {
        return repository.artifactContent(companyId, get(companyId, invoiceId).id(), kind)
                .orElseThrow(() -> new MystixException(ErrorCode.INVOICE_NOT_FOUND,
                        "No " + kind + " artifact for invoice " + invoiceId));
    }

    private Submission replay(StoredInvoice existing, String canonicalSha256) {
        if (!existing.canonicalSha256().equals(canonicalSha256)) {
            throw new MystixException(ErrorCode.INVOICE_NUMBER_CONFLICT,
                    "Invoice number " + existing.number() + " already used with different content");
        }
        return new Submission(existing, artifact(existing.companyId(), existing.id(), ArtifactKind.OUT), true);
    }

    private byte[] toUbl(Invoice invoice, InvoiceTotals totals) {
        byte[] ubl = generator.generate(invoice, totals);

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
