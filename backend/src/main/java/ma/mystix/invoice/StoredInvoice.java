package ma.mystix.invoice;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

import ma.mystix.shared.time.TimeConfig;

/**
 * An accepted invoice, always read within its company.
 *
 * @param clearanceReference identifier returned by the clearance gateway, {@code null} until cleared
 * @param clearanceSimulated true when the clearance answer is simulated (ADR-0001), {@code null} before clearance
 * @param clearanceAt        time of the clearance answer, {@code null} before clearance
 * @param buyerName          BT-44, for lists; {@code null} for invoices stored before summaries existed
 * @param currency           BT-5, for lists; may be {@code null} likewise
 * @param payableAmount      BT-115, for lists; may be {@code null} likewise
 * @param flowId             exchange flow the invoice entered, {@code null} for invoices stored before flows
 */
public record StoredInvoice(UUID id, UUID companyId, String number, LocalDate issueDate, String status,
                            String canonicalVersion, String canonicalSha256, OffsetDateTime createdAt,
                            String clearanceReference, Boolean clearanceSimulated, OffsetDateTime clearanceAt,
                            String buyerName, String currency, BigDecimal payableAmount, UUID flowId) {

    /** Backdated: held until the environment administrator approves or rejects it (ADR-0010). */
    public static final String STATUS_PENDING_VALIDATION = "PENDING_VALIDATION";
    public static final String STATUS_VALIDATION_REJECTED = "VALIDATION_REJECTED";
    public static final String STATUS_VALIDATED = "VALIDATED";
    public static final String STATUS_CLEARED = "CLEARED";
    public static final String STATUS_CLEARANCE_REJECTED = "CLEARANCE_REJECTED";
    /** History-only status: the gateway could not be reached; the invoice stays VALIDATED. */
    public static final String EVENT_CLEARANCE_ERROR = "CLEARANCE_ERROR";

    /**
     * Issued before the day it was received (Africa/Casablanca): a backdated invoice. Derived from stored facts,
     * so it never changes afterwards.
     */
    public boolean backdated() {
        return issueDate.isBefore(createdAt.atZoneSameInstant(TimeConfig.BUSINESS_ZONE).toLocalDate());
    }

    /** Metadata of a stored artefact, without its content. */
    public record ArtifactInfo(ArtifactKind kind, String mediaType, String sha256, long size) {
    }

    /** One entry of the append-only status history. */
    public record StatusEvent(String status, String detail, OffsetDateTime occurredAt) {
    }
}
