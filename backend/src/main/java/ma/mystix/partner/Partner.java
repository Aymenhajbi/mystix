package ma.mystix.partner;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * A trading partner of a client environment.
 *
 * @param ice       Moroccan ICE when the partner is a Moroccan company, optional
 * @param gln       GS1 GLN, optional
 * @param reference the client's own code for this partner (ERP vendor or customer number), optional
 */
public record Partner(UUID id, UUID companyId, String name, Type type, String ice, String gln, String reference,
                      OffsetDateTime createdAt) {

    public enum Type { CUSTOMER, SUPPLIER, TAX_AUTHORITY, LOGISTICS, BANK, OTHER }
}
