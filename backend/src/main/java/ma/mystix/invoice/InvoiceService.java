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
import ma.mystix.flow.ExchangeFlow;
import ma.mystix.logs.LogEvent;
import ma.mystix.logs.ProcessingLog;
import ma.mystix.shared.Sha256;
import ma.mystix.shared.error.ApiError;
import ma.mystix.shared.error.ErrorCode;
import ma.mystix.shared.error.MystixException;
import ma.mystix.shared.metrics.StageMetrics;
import ma.mystix.queue.Job;
import ma.mystix.queue.MessageQueue;
import ma.mystix.shared.time.TimeConfig;
import ma.mystix.tenant.CompanyService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

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
    private static final String PIPELINE = "invoice";

    private final CompanyService companies;
    private final InvoiceRepository repository;
    private final InvoiceCalculator calculator;
    private final UblInvoiceGenerator generator;
    private final UblSchemaValidator schemaValidator;
    private final En16931Validator rulesValidator;
    private final ClearanceGateway clearance;
    private final ProcessingLog logs;
    private final StageMetrics stages;
    private final MessageQueue queue;
    private final TransactionTemplate tx;
    private final Clock clock;

    InvoiceService(CompanyService companies, InvoiceRepository repository, InvoiceCalculator calculator,
                   UblInvoiceGenerator generator, UblSchemaValidator schemaValidator,
                   En16931Validator rulesValidator, ClearanceGateway clearance, ProcessingLog logs, StageMetrics stages,
                   MessageQueue queue, TransactionTemplate tx, Clock clock) {
        this.stages = stages;
        this.queue = queue;
        this.tx = tx;
        this.clearance = clearance;
        this.logs = logs;
        this.companies = companies;
        this.repository = repository;
        this.calculator = calculator;
        this.generator = generator;
        this.schemaValidator = schemaValidator;
        this.rulesValidator = rulesValidator;
        this.clock = clock;
    }

    /** Queue job that submits one invoice for clearance (ADR-0003); its subject is the invoice id. */
    public static final String CLEARANCE_JOB = "INVOICE_CLEARANCE";
    private static final int CLEARANCE_PRIORITY = 5;

    /**
     * Result of a submission; {@code replayed} is true when the invoice had already been accepted,
     * {@code clearanceQueued} when its clearance runs in the queue rather than in the request.
     */
    public record Submission(StoredInvoice invoice, byte[] ubl, boolean replayed, boolean clearanceQueued) {
    }

    /** Synchronous submission: clearance in the request, as before the queue. */
    public Submission submit(UUID companyId, ExchangeFlow flow, byte[] rawRequest, Invoice invoice) {
        return submit(companyId, flow, rawRequest, invoice, false);
    }

    /**
     * Accepts an invoice. With {@code async}, the clearance job is written in the same transaction as the invoice and
     * the call returns at once; otherwise clearance runs in the request, and only a gateway failure goes to the queue.
     */
    public Submission submit(UUID companyId, ExchangeFlow flow, byte[] rawRequest, Invoice invoice, boolean async) {
        // Seller identity (seller ICE = company ICE) is checked at intake, where each environment can turn it off.
        companies.get(companyId);

        byte[] canonical = stages.time(PIPELINE, "canonical", () -> CanonicalJson.write(invoice));
        String canonicalSha256 = Sha256.hex(canonical);

        var existing = repository.findByNumber(companyId, invoice.number());
        if (existing.isPresent()) {
            return replay(existing.get(), canonicalSha256);
        }

        InvoiceTotals totals = stages.time(PIPELINE, "calculate", () -> calculator.calculate(invoice));
        byte[] ubl = toUbl(invoice, totals);
        OffsetDateTime now = OffsetDateTime.now(clock);
        // A backdated invoice is stored and checked like any other, but waits for an administrator before clearance.
        boolean backdated = invoice.issueDate().isBefore(now.atZoneSameInstant(TimeConfig.BUSINESS_ZONE).toLocalDate());
        StoredInvoice stored = new StoredInvoice(UUID.randomUUID(), companyId, invoice.number(), invoice.issueDate(),
                backdated ? StoredInvoice.STATUS_PENDING_VALIDATION : StoredInvoice.STATUS_VALIDATED,
                CanonicalVersion.CURRENT, canonicalSha256, now,
                null, null, null, invoice.buyer().name(), invoice.currency().getCurrencyCode(),
                totals.payableAmount(), flow.id());
        Map<ArtifactKind, byte[]> artifacts = new EnumMap<>(ArtifactKind.class);
        artifacts.put(ArtifactKind.RAW, rawRequest);
        artifacts.put(ArtifactKind.CANONICAL, canonical);
        artifacts.put(ArtifactKind.OUT, ubl);
        try {
            boolean enqueue = async && !backdated;
            stages.time(PIPELINE, "persist", () -> tx.executeWithoutResult(status -> {
                repository.insert(stored, artifacts);
                if (enqueue) {
                    enqueueClearance(stored);
                }
            }));
        } catch (DuplicateKeyException e) {
            // A concurrent submission with the same number won the race.
            return replay(repository.findByNumber(companyId, invoice.number()).orElseThrow(() -> e),
                    canonicalSha256);
        }
        logs.invoiceAccepted(companyId, stored.id(), stored.number(),
                "Invoice accepted: UBL 2.1 generated (" + ubl.length + " bytes), XSD valid, EN 16931 "
                        + En16931Validator.ARTEFACTS_VERSION + " compliant, stored with RAW, CANONICAL and OUT");
        if (backdated) {
            return new Submission(stored, ubl, false, false);
        }
        if (async) {
            logs.clearance(companyId, stored.id(), stored.number(), LogEvent.CLEARANCE_QUEUED,
                    "Clearance queued (asynchronous submission)");
            return new Submission(stored, ubl, false, true);
        }
        return new Submission(clear(stored, ubl), ubl, false, false);
    }

    /**
     * Administrator decision on a backdated invoice: approved, it goes to clearance; rejected, it stops there.
     * TODO(auth): restricted to the environment administrator role (Lot 8).
     */
    public StoredInvoice decideValidation(UUID companyId, UUID invoiceId, boolean approve, String comment) {
        StoredInvoice invoice = get(companyId, invoiceId);
        String note = comment == null || comment.isBlank() ? "" : ": " + comment.strip();
        String detail = (approve ? "Backdated invoice approved by the administrator"
                : "Backdated invoice rejected by the administrator") + note;
        if (!repository.decideValidation(companyId, invoiceId,
                approve ? StoredInvoice.STATUS_VALIDATED : StoredInvoice.STATUS_VALIDATION_REJECTED, detail,
                OffsetDateTime.now(clock))) {
            throw new MystixException(ErrorCode.INVOICE_NOT_PENDING_VALIDATION,
                    "Invoice " + invoiceId + " is " + invoice.status() + ", not pending validation");
        }
        logs.validationDecided(companyId, invoiceId, invoice.number(), approve, detail);
        StoredInvoice decided = get(companyId, invoiceId);
        return approve ? clear(decided, artifact(companyId, invoiceId, ArtifactKind.OUT)) : decided;
    }

    /** Most recent invoices of the company first. */
    public List<StoredInvoice> list(UUID companyId, UUID flowId, int limit) {
        companies.get(companyId);
        return repository.list(companyId, flowId, Math.clamp(limit, 1, MAX_PAGE_SIZE));
    }

    public List<StoredInvoice.StatusEvent> history(UUID companyId, UUID invoiceId) {
        return repository.history(companyId, get(companyId, invoiceId).id());
    }

    /**
     * Submits a stored invoice for clearance in the request. A gateway failure leaves the invoice VALIDATED, is
     * recorded in its history, and hands the clearance to the queue, which retries it with backoff (ADR-0003).
     */
    private StoredInvoice clear(StoredInvoice stored, byte[] ubl) {
        try {
            return submitToGateway(stored, ubl);
        } catch (RuntimeException e) {
            log.warn("Clearance failed for invoice {}, retry queued", stored.id(), e);
            tx.executeWithoutResult(status -> {
                repository.appendEvent(stored.companyId(), stored.id(), StoredInvoice.EVENT_CLEARANCE_ERROR,
                        "Clearance gateway error; invoice stays VALIDATED, retry queued", OffsetDateTime.now(clock));
                enqueueClearance(stored);
            });
            logs.clearance(stored.companyId(), stored.id(), stored.number(), LogEvent.CLEARANCE_ERROR,
                    "Clearance gateway error (" + e.getClass().getSimpleName()
                            + "); invoice stays VALIDATED, retry queued");
            return stored;
        }
    }

    /**
     * Clearance run by the queue. Idempotent: an invoice that is no longer VALIDATED (already cleared or rejected,
     * or waiting for validation) is left as it is. A gateway failure propagates, so the queue retries it.
     */
    public void clearQueued(UUID companyId, UUID invoiceId) {
        StoredInvoice stored = get(companyId, invoiceId);
        if (!StoredInvoice.STATUS_VALIDATED.equals(stored.status())) {
            return;
        }
        submitToGateway(stored, artifact(companyId, invoiceId, ArtifactKind.OUT));
    }

    private void enqueueClearance(StoredInvoice stored) {
        queue.enqueue(new Job.New(stored.companyId(),
                stored.flowId() == null ? Job.companyLane(stored.companyId()) : Job.flowLane(stored.flowId()),
                CLEARANCE_JOB, stored.id(), Map.of("number", stored.number()), CLEARANCE_PRIORITY));
    }

    private StoredInvoice submitToGateway(StoredInvoice stored, byte[] ubl) {
        ClearanceResult result = stages.time(PIPELINE, "clearance", () -> clearance.submit(new ClearanceRequest(
                stored.companyId(), stored.id(), stored.number(), ubl, Sha256.hex(ubl))));
        boolean cleared = result.outcome() == ClearanceResult.Outcome.CLEARED;
        String detail = (result.simulated() ? "SIMULATED clearance: " : "")
                + (cleared ? "cleared" : result.reason());
        repository.recordClearance(stored.companyId(), stored.id(),
                cleared ? StoredInvoice.STATUS_CLEARED : StoredInvoice.STATUS_CLEARANCE_REJECTED,
                result.reference(), result.simulated(), result.at(), detail);
        logs.clearance(stored.companyId(), stored.id(), stored.number(),
                cleared ? LogEvent.CLEARANCE_CLEARED : LogEvent.CLEARANCE_REJECTED,
                cleared ? detail + ", reference " + result.reference() : detail);
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
        logs.invoiceReplayed(existing.companyId(), existing.id(), existing.number());
        return new Submission(existing, artifact(existing.companyId(), existing.id(), ArtifactKind.OUT), true, false);
    }

    /** UBL 2.1 for an invoice, checked against the XSD and EN 16931; throws on failure. No side effect. */
    byte[] checkedUbl(Invoice invoice) {
        return toUbl(invoice, calculator.calculate(invoice));
    }

    private byte[] toUbl(Invoice invoice, InvoiceTotals totals) {
        byte[] ubl = stages.time(PIPELINE, "generate", () -> generator.generate(invoice, totals));

        List<String> schemaErrors = stages.time(PIPELINE, "xsd", () -> schemaValidator.validateInvoice(ubl));
        if (!schemaErrors.isEmpty()) {
            // Our own output broke the schema: a Mystix bug, not a user error.
            throw new MystixException(ErrorCode.INVOICE_SCHEMA_INVALID,
                    "Generated UBL is not schema-valid for " + invoice.number() + ": " + schemaErrors);
        }

        List<En16931Validator.Violation> violations = stages.time(PIPELINE, "en16931", () -> rulesValidator.validate(ubl));
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
