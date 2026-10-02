package ma.mystix.invoice;

import java.math.RoundingMode;
import java.net.URI;
import java.util.Currency;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import ma.mystix.format.ubl.En16931Validator;
import ma.mystix.format.ubl.UblMappingSpec;
import ma.mystix.logs.ProcessingLog;
import ma.mystix.shared.error.ApiError;
import ma.mystix.shared.error.ErrorCode;
import ma.mystix.shared.error.MystixException;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * Invoice API. The company is identified by the {@value #COMPANY_HEADER} header.
 * TODO(auth): replace the header with the company of the authenticated principal (Lot 8, OIDC).
 */
@RestController
@RequestMapping("/api/v1/invoices")
class InvoiceController {

    static final String COMPANY_HEADER = "X-Mystix-Company-Id";
    static final String INVOICE_ID_HEADER = "X-Mystix-Invoice-Id";
    static final String REPLAYED_HEADER = "X-Mystix-Replayed";
    static final String CANONICAL_VERSION_HEADER = "X-Mystix-Canonical-Version";
    static final String EN16931_HEADER = "X-Mystix-En16931-Artefacts";
    static final String STATUS_HEADER = "X-Mystix-Status";
    static final String CLEARANCE_REFERENCE_HEADER = "X-Mystix-Clearance-Reference";
    static final String CLEARANCE_SIMULATED_HEADER = "X-Mystix-Clearance-Simulated";

    private final InvoiceService service;
    private final InvoiceLineage lineage;
    private final ProcessingLog logs;
    private final JsonMapper json;
    private final Validator validator;

    InvoiceController(InvoiceService service, InvoiceLineage lineage, ProcessingLog logs, JsonMapper json,
                      Validator validator) {
        this.service = service;
        this.lineage = lineage;
        this.logs = logs;
        this.json = json;
        this.validator = validator;
    }

    /**
     * Accepts a JSON invoice and returns its UBL 2.1 (XSD-valid, EN 16931 compliant).
     * 201 on first acceptance, 200 with {@value #REPLAYED_HEADER}: true when the same invoice is sent again.
     * The clearance outcome is in {@value #STATUS_HEADER}; clearance is SIMULATED (ADR-0001), see
     * {@value #CLEARANCE_SIMULATED_HEADER}.
     */
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_XML_VALUE)
    ResponseEntity<byte[]> submit(@RequestHeader(COMPANY_HEADER) UUID companyId, @RequestBody byte[] body) {
        InvoiceRequest request = null;
        InvoiceService.Submission submission;
        try {
            request = parse(body);
            submission = service.submit(companyId, body, InvoiceRequestMapper.toCanonical(request));
        } catch (MystixException e) {
            logs.submissionRejected(companyId, request == null ? null : request.number(), e, body);
            throw e;
        } catch (RuntimeException e) {
            logs.submissionFailed(companyId, request == null ? null : request.number(), e, body);
            throw e;
        }
        StoredInvoice invoice = submission.invoice();
        ResponseEntity.BodyBuilder response = submission.replayed()
                ? ResponseEntity.ok()
                : ResponseEntity.created(URI.create("/api/v1/invoices/" + invoice.id()));
        response.contentType(MediaType.APPLICATION_XML)
                .header(INVOICE_ID_HEADER, invoice.id().toString())
                .header(REPLAYED_HEADER, Boolean.toString(submission.replayed()))
                .header(STATUS_HEADER, invoice.status())
                .header(CANONICAL_VERSION_HEADER, invoice.canonicalVersion())
                .header(EN16931_HEADER, En16931Validator.ARTEFACTS_VERSION);
        if (invoice.clearanceSimulated() != null) {
            response.header(CLEARANCE_SIMULATED_HEADER, invoice.clearanceSimulated().toString());
        }
        if (invoice.clearanceReference() != null) {
            response.header(CLEARANCE_REFERENCE_HEADER, invoice.clearanceReference());
        }
        return response.body(submission.ubl());
    }

    /** Clearance block of the invoice resource. {@code simulated} is always shown, never implied. */
    record ClearanceView(String reference, Boolean simulated, OffsetDateTime at) {

        static ClearanceView from(StoredInvoice i) {
            return new ClearanceView(i.clearanceReference(), i.clearanceSimulated(), i.clearanceAt());
        }
    }

    /** List item. {@code payableAmount} is a decimal string at the currency's precision, null for old invoices. */
    record InvoiceSummary(UUID id, String number, LocalDate issueDate, String buyerName, String currency,
                          String payableAmount, String status, ClearanceView clearance, OffsetDateTime createdAt) {

        static InvoiceSummary from(StoredInvoice i) {
            return new InvoiceSummary(i.id(), i.number(), i.issueDate(), i.buyerName(), i.currency(),
                    amount(i), i.status(), ClearanceView.from(i), i.createdAt());
        }
    }

    record InvoiceResponse(UUID id, String number, LocalDate issueDate, String buyerName, String currency,
                           String payableAmount, String status, String canonicalVersion, OffsetDateTime createdAt,
                           ClearanceView clearance, List<StoredInvoice.ArtifactInfo> artifacts,
                           List<StoredInvoice.StatusEvent> history) {
    }

    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    List<InvoiceSummary> list(@RequestHeader(COMPANY_HEADER) UUID companyId,
                              @RequestParam(defaultValue = "50") int limit) {
        return service.list(companyId, limit).stream().map(InvoiceSummary::from).toList();
    }

    @GetMapping(value = "/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    InvoiceResponse get(@RequestHeader(COMPANY_HEADER) UUID companyId, @PathVariable UUID id) {
        StoredInvoice invoice = service.get(companyId, id);
        return new InvoiceResponse(invoice.id(), invoice.number(), invoice.issueDate(), invoice.buyerName(),
                invoice.currency(), amount(invoice), invoice.status(), invoice.canonicalVersion(),
                invoice.createdAt(), ClearanceView.from(invoice),
                service.artifacts(companyId, id), service.history(companyId, id));
    }

    private static String amount(StoredInvoice invoice) {
        if (invoice.payableAmount() == null || invoice.currency() == null) {
            return null;
        }
        int digits = Currency.getInstance(invoice.currency()).getDefaultFractionDigits();
        return invoice.payableAmount().setScale(digits, RoundingMode.UNNECESSARY).toPlainString();
    }

    record LineageResponse(String mapping, String mappingVersion, List<InvoiceLineage.Row> rows) {
    }

    /** Field lineage read from the stored RAW, CANONICAL and OUT artefacts (see {@link UblMappingSpec}). */
    @GetMapping(value = "/{id}/lineage", produces = MediaType.APPLICATION_JSON_VALUE)
    LineageResponse lineage(@RequestHeader(COMPANY_HEADER) UUID companyId, @PathVariable UUID id) {
        return new LineageResponse(UblMappingSpec.ID, UblMappingSpec.VERSION, lineage.of(companyId, id));
    }

    @GetMapping("/{id}/artifacts/{kind}")
    ResponseEntity<byte[]> artifact(@RequestHeader(COMPANY_HEADER) UUID companyId, @PathVariable UUID id,
                                    @PathVariable ArtifactKind kind) {
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(kind.mediaType()))
                .body(service.artifact(companyId, id, kind));
    }

    private InvoiceRequest parse(byte[] body) {
        InvoiceRequest request;
        try {
            request = json.readValue(body, InvoiceRequest.class);
        } catch (JacksonException e) {
            throw new MystixException(ErrorCode.VALIDATION_FAILED, "Unreadable invoice JSON: " + e.getOriginalMessage());
        }
        if (request == null) {
            throw new MystixException(ErrorCode.VALIDATION_FAILED, "Empty invoice body");
        }
        Set<ConstraintViolation<InvoiceRequest>> violations = validator.validate(request);
        if (!violations.isEmpty()) {
            throw new MystixException(ErrorCode.VALIDATION_FAILED, "Invalid invoice request",
                    violations.stream()
                            .map(v -> new ApiError.FieldViolation(v.getPropertyPath().toString(), v.getMessage()))
                            .sorted(Comparator.comparing(ApiError.FieldViolation::field))
                            .toList(),
                    List.of(), null);
        }
        return request;
    }
}
