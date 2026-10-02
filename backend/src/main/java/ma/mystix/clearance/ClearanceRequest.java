package ma.mystix.clearance;

import java.util.Objects;
import java.util.UUID;

/**
 * What Mystix sends for clearance: the generated document and its fingerprint.
 *
 * @param companyId     submitting company
 * @param invoiceId     Mystix identifier of the invoice
 * @param invoiceNumber BT-1
 * @param document      generated invoice (UBL 2.1). TODO(DGI-SPEC): format expected by the DGI.
 * @param documentSha256 SHA-256 of {@code document}
 */
public record ClearanceRequest(UUID companyId, UUID invoiceId, String invoiceNumber, byte[] document,
                               String documentSha256) {

    public ClearanceRequest {
        Objects.requireNonNull(companyId, "companyId");
        Objects.requireNonNull(invoiceId, "invoiceId");
        Objects.requireNonNull(invoiceNumber, "invoiceNumber");
        Objects.requireNonNull(document, "document");
        Objects.requireNonNull(documentSha256, "documentSha256");
    }
}
