package ma.mystix.tenant;

import java.time.OffsetDateTime;
import java.util.UUID;

/** A company is the tenant root: all business data belongs to exactly one company. */
public record Company(UUID id, Ice ice, String legalName, String taxIdentifier, OffsetDateTime createdAt) {
}
