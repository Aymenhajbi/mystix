package ma.mystix.invoice;

import java.math.RoundingMode;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Invoice processing settings ({@code mystix.invoice.*}).
 * TODO(DGI-SPEC): every value below is provisional until the DGI technical specifications are published.
 *
 * @param roundingMode rounding of line nets and VAT amounts
 * @param ubl          UBL output identifiers, see ADR-0005
 */
@ConfigurationProperties("mystix.invoice")
public record InvoiceProperties(RoundingMode roundingMode, Ubl ubl) {

    public InvoiceProperties {
        roundingMode = roundingMode == null ? RoundingMode.HALF_UP : roundingMode;
        ubl = ubl == null ? new Ubl(null, null, null) : ubl;
    }

    public record Ubl(String customizationId, String iceSchemeId, String taxIdentifierSchemeId) {

        public Ubl {
            customizationId = customizationId == null ? "urn:cen.eu:en16931:2017" : customizationId;
            iceSchemeId = iceSchemeId == null || iceSchemeId.isBlank() ? null : iceSchemeId;
            taxIdentifierSchemeId = taxIdentifierSchemeId == null ? "TAX" : taxIdentifierSchemeId;
        }
    }
}
