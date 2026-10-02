package ma.mystix.invoice;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

/** An accepted invoice, always read within its company. */
public record StoredInvoice(UUID id, UUID companyId, String number, LocalDate issueDate, String status,
                            String canonicalVersion, String canonicalSha256, OffsetDateTime createdAt) {

    public static final String STATUS_VALIDATED = "VALIDATED";

    /** Metadata of a stored artefact, without its content. */
    public record ArtifactInfo(ArtifactKind kind, String mediaType, String sha256, long size) {
    }
}
