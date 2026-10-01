package ma.mystix.format.ubl;

import java.util.Objects;

/**
 * Values the UBL output depends on that no published Moroccan specification fixes yet.
 *
 * @param customizationId       BT-24 specification identifier.
 *                              TODO(DGI-SPEC): replace with the Moroccan CIUS identifier once published.
 * @param iceSchemeId           schemeID written on the ICE (BT-30 / BT-47), or {@code null} for none.
 *                              TODO(DGI-SPEC): no ISO 6523 ICD is known for the ICE.
 * @param taxIdentifierSchemeId cac:TaxScheme/cbc:ID used for the Identifiant Fiscal (BT-32).
 *                              TODO(DGI-SPEC): to confirm with the DGI specifications.
 */
public record UblSettings(String customizationId, String iceSchemeId, String taxIdentifierSchemeId) {

    public UblSettings {
        Objects.requireNonNull(customizationId, "customizationId");
        Objects.requireNonNull(taxIdentifierSchemeId, "taxIdentifierSchemeId");
    }

    /** EN 16931 core customization, no ICE scheme, IF under the "TAX" scheme. */
    public static UblSettings defaults() {
        return new UblSettings("urn:cen.eu:en16931:2017", null, "TAX");
    }
}
