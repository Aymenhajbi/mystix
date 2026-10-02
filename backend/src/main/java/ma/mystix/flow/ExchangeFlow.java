package ma.mystix.flow;

import java.time.OffsetDateTime;
import java.util.UUID;

/** An exchange flow of one client environment (company). */
public record ExchangeFlow(UUID id, UUID companyId, String name, String documentType, String direction,
                           String sourceChannel, String sourceFormat, String targetFormat, String targetChannel,
                           String mappingId, String mappingVersion, Status status, OffsetDateTime createdAt,
                           OffsetDateTime updatedAt) {

    public enum Status { DRAFT, ACTIVE, PAUSED }

    public static final String DEFAULT_NAME = "Factures API vers UBL 2.1";
}
