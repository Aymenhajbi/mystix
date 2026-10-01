package ma.mystix.canonical;

import java.util.Objects;

/**
 * BG-4 Seller / BG-7 Buyer.
 *
 * @param name          BT-27 / BT-44 legal name
 * @param ice           Moroccan ICE (15 digits), carried as BT-30 / BT-47 legal registration identifier, optional
 * @param taxIdentifier Moroccan Identifiant Fiscal (IF), carried as BT-32 tax registration identifier, optional
 * @param tradeRegister Moroccan Registre de Commerce (RC); no EN 16931 term, canonical only, optional
 * @param gln           GS1 Global Location Number, carried as BT-29 / BT-46 with scheme 0088, optional
 * @param address       BG-5 / BG-8
 */
public record Party(String name, String ice, String taxIdentifier, String tradeRegister, String gln,
                    PostalAddress address) {

    public Party {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Party name is required");
        }
        if (ice != null && !ice.matches("^[0-9]{15}$")) {
            throw new IllegalArgumentException("ICE must be exactly 15 digits");
        }
        if (gln != null && !Gs1.isValidGln(gln)) {
            throw new IllegalArgumentException("GLN must be 13 digits with a valid GS1 check digit");
        }
        Objects.requireNonNull(address, "address");
    }
}
