package ma.mystix.invoice;

import jakarta.validation.Valid;
import ma.mystix.canonical.CanonicalVersion;
import ma.mystix.format.ubl.En16931Validator;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/invoices")
class InvoiceController {

    static final String CANONICAL_VERSION_HEADER = "X-Mystix-Canonical-Version";
    static final String EN16931_HEADER = "X-Mystix-En16931-Artefacts";

    private final InvoiceService service;

    InvoiceController(InvoiceService service) {
        this.service = service;
    }

    /**
     * Converts a JSON invoice into a UBL 2.1 invoice that passed the UBL XSD and the EN 16931 rules.
     * Nothing is stored and nothing is sent to the DGI (clearance is simulated and not part of this call).
     */
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_XML_VALUE)
    ResponseEntity<byte[]> toUbl(@Valid @RequestBody InvoiceRequest request) {
        byte[] ubl = service.toUbl(InvoiceRequestMapper.toCanonical(request));
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_XML)
                .header(CANONICAL_VERSION_HEADER, CanonicalVersion.CURRENT)
                .header(EN16931_HEADER, En16931Validator.ARTEFACTS_VERSION)
                .body(ubl);
    }
}
