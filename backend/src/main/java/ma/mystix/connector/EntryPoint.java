package ma.mystix.connector;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Where a message enters the Mystix pipeline (ADR-0016): the target flow, and the document type and format expected.
 *
 * @param partnerId the partner expected on this entry point, optional
 */
public record EntryPoint(UUID id, UUID companyId, String code, String name, UUID flowId, DocumentType documentType,
                         InputFormat inputFormat, UUID partnerId, boolean enabled, String createdBy,
                         OffsetDateTime createdAt, OffsetDateTime updatedAt) {

    public enum DocumentType { INVOICE }

    /** Formats an entry point may declare; only JSON is handed to the pipeline today (see {@link #dispatchable()}). */
    public enum InputFormat { JSON, XML, EDIFACT, CSV }

    /** True when the pipeline can take this document type in this format. */
    public boolean dispatchable() {
        return documentType == DocumentType.INVOICE && inputFormat == InputFormat.JSON;
    }
}
