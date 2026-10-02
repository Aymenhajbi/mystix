package ma.mystix.invoice;

import java.net.URI;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import ma.mystix.format.ubl.En16931Validator;
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

    private final InvoiceService service;
    private final JsonMapper json;
    private final Validator validator;

    InvoiceController(InvoiceService service, JsonMapper json, Validator validator) {
        this.service = service;
        this.json = json;
        this.validator = validator;
    }

    /**
     * Accepts a JSON invoice and returns its UBL 2.1 (XSD-valid, EN 16931 compliant).
     * 201 on first acceptance, 200 with {@value #REPLAYED_HEADER}: true when the same invoice is sent again.
     * Clearance is simulated and not part of this call.
     */
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_XML_VALUE)
    ResponseEntity<byte[]> submit(@RequestHeader(COMPANY_HEADER) UUID companyId, @RequestBody byte[] body) {
        InvoiceRequest request = parse(body);
        InvoiceService.Submission submission =
                service.submit(companyId, body, InvoiceRequestMapper.toCanonical(request));
        UUID id = submission.invoice().id();
        ResponseEntity.BodyBuilder response = submission.replayed()
                ? ResponseEntity.ok()
                : ResponseEntity.created(URI.create("/api/v1/invoices/" + id));
        return response
                .contentType(MediaType.APPLICATION_XML)
                .header(INVOICE_ID_HEADER, id.toString())
                .header(REPLAYED_HEADER, Boolean.toString(submission.replayed()))
                .header(CANONICAL_VERSION_HEADER, submission.invoice().canonicalVersion())
                .header(EN16931_HEADER, En16931Validator.ARTEFACTS_VERSION)
                .body(submission.ubl());
    }

    record InvoiceResponse(UUID id, String number, LocalDate issueDate, String status, String canonicalVersion,
                           OffsetDateTime createdAt, List<StoredInvoice.ArtifactInfo> artifacts) {
    }

    @GetMapping(value = "/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    InvoiceResponse get(@RequestHeader(COMPANY_HEADER) UUID companyId, @PathVariable UUID id) {
        StoredInvoice invoice = service.get(companyId, id);
        return new InvoiceResponse(invoice.id(), invoice.number(), invoice.issueDate(), invoice.status(),
                invoice.canonicalVersion(), invoice.createdAt(), service.artifacts(companyId, id));
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
