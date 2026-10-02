package ma.mystix.format.ubl;

import java.util.List;

/**
 * Field-by-field description of what {@link UblInvoiceGenerator} does: request JSON → canonical model → UBL 2.1.
 * It documents the code, it does not drive it. {@code UblMappingSpecTest} checks every target XPath against the
 * generator's real output, so this description cannot silently drift from the generator.
 * <p>
 * Paths: {@code request} and {@code canonical} are dotted JSON paths; {@code {i}} stands for a line index
 * (0-based) and {@code {n}} for the same line in XPath (1-based). Prefixes: inv, cac, cbc.
 */
public final class UblMappingSpec {

    public static final String ID = "ubl-invoice";
    public static final String VERSION = "1.0";

    /** How the target value is produced. */
    public enum Kind {
        /** Copied as is. */
        DIRECT,
        /** Fixed by the generator. */
        CONSTANT,
        /** Computed (EN 16931 calculation model). */
        CALCULATED,
        /** Taken from configuration ({@code mystix.invoice.ubl}); TODO(DGI-SPEC). */
        CONFIGURED,
        /** Kept in the canonical model but not written to UBL. */
        NOT_EMITTED
    }

    /**
     * @param group     section of the view: header, seller, buyer, line, totals
     * @param term      EN 16931 business term (BT-x), or {@code null}
     * @param label     what the field is, in French
     * @param request   path in the request JSON, or {@code null}
     * @param canonical path in the canonical JSON, or {@code null}
     * @param target    XPath in the UBL output, or {@code null} when not emitted
     * @param kind      how the value is produced
     * @param rule      transformation or decision, in French
     */
    public record Field(String group, String term, String label, String request, String canonical, String target,
                        Kind kind, String rule) {
    }

    private static final String SELLER = "/inv:Invoice/cac:AccountingSupplierParty/cac:Party";
    private static final String BUYER = "/inv:Invoice/cac:AccountingCustomerParty/cac:Party";
    private static final String LINE = "/inv:Invoice/cac:InvoiceLine[{n}]";
    private static final String TOTAL = "/inv:Invoice/cac:LegalMonetaryTotal";

    public static final List<Field> FIELDS = List.of(
            new Field("header", "BT-24", "Spécification", null, null, "/inv:Invoice/cbc:CustomizationID",
                    Kind.CONFIGURED, "Valeur de configuration, provisoire en attendant la CIUS marocaine (ADR-0005)"),
            new Field("header", "BT-1", "Numéro de facture", "number", "invoice.number", "/inv:Invoice/cbc:ID",
                    Kind.DIRECT, "Copié tel quel"),
            new Field("header", "BT-2", "Date d'émission", "issueDate", "invoice.issueDate",
                    "/inv:Invoice/cbc:IssueDate", Kind.DIRECT, "Date ISO 8601"),
            new Field("header", "BT-9", "Échéance", "dueDate", "invoice.dueDate", "/inv:Invoice/cbc:DueDate",
                    Kind.DIRECT, "Date ISO 8601, omise si absente"),
            new Field("header", "BT-3", "Type de facture", null, "invoice.typeCode",
                    "/inv:Invoice/cbc:InvoiceTypeCode", Kind.CONSTANT, "Facture commerciale → code UNTDID 1001 380"),
            new Field("header", "BT-22", "Note", "note", "invoice.note", "/inv:Invoice/cbc:Note", Kind.DIRECT,
                    "Copiée, caractères XML échappés"),
            new Field("header", "BT-5", "Devise", "currency", "invoice.currency",
                    "/inv:Invoice/cbc:DocumentCurrencyCode", Kind.DIRECT, "Code ISO 4217"),
            new Field("header", "BT-10", "Référence acheteur", "buyerReference", "invoice.buyerReference",
                    "/inv:Invoice/cbc:BuyerReference", Kind.DIRECT, "Copiée, omise si absente"),
            new Field("header", "BT-13", "Bon de commande", "purchaseOrderReference",
                    "invoice.purchaseOrderReference", "/inv:Invoice/cac:OrderReference/cbc:ID", Kind.DIRECT,
                    "Copié, omis si absent"),

            new Field("seller", "BT-27", "Raison sociale", "seller.name", "invoice.seller.name",
                    SELLER + "/cac:PartyLegalEntity/cbc:RegistrationName", Kind.DIRECT, "Copiée"),
            new Field("seller", "BT-30", "ICE", "seller.ice", "invoice.seller.ice",
                    SELLER + "/cac:PartyLegalEntity/cbc:CompanyID", Kind.DIRECT,
                    "15 chiffres contrôlés ; schemeID configurable (ADR-0005)"),
            new Field("seller", "BT-32", "Identifiant fiscal (IF)", "seller.taxIdentifier",
                    "invoice.seller.taxIdentifier", SELLER + "/cac:PartyTaxScheme/cbc:CompanyID", Kind.DIRECT,
                    "TaxScheme configurable, TAX par défaut (ADR-0005)"),
            new Field("seller", null, "Registre de commerce (RC)", "seller.tradeRegister",
                    "invoice.seller.tradeRegister", null, Kind.NOT_EMITTED,
                    "Aucun terme EN 16931 : conservé dans le canonique seulement (ADR-0005)"),
            new Field("seller", "BT-29", "GLN", "seller.gln", "invoice.seller.gln",
                    SELLER + "/cac:PartyIdentification/cbc:ID", Kind.DIRECT,
                    "Chiffre de contrôle GS1 vérifié ; schemeID 0088"),
            new Field("seller", "BT-37", "Ville", "seller.address.city", "invoice.seller.address.city",
                    SELLER + "/cac:PostalAddress/cbc:CityName", Kind.DIRECT, "Copiée"),
            new Field("seller", "BT-40", "Pays", "seller.address.countryCode", "invoice.seller.address.countryCode",
                    SELLER + "/cac:PostalAddress/cac:Country/cbc:IdentificationCode", Kind.DIRECT, "ISO 3166-1"),

            new Field("buyer", "BT-44", "Raison sociale", "buyer.name", "invoice.buyer.name",
                    BUYER + "/cac:PartyLegalEntity/cbc:RegistrationName", Kind.DIRECT, "Copiée"),
            new Field("buyer", "BT-47", "ICE", "buyer.ice", "invoice.buyer.ice",
                    BUYER + "/cac:PartyLegalEntity/cbc:CompanyID", Kind.DIRECT, "15 chiffres contrôlés"),
            new Field("buyer", "BT-46", "GLN", "buyer.gln", "invoice.buyer.gln",
                    BUYER + "/cac:PartyIdentification/cbc:ID", Kind.DIRECT, "Chiffre de contrôle GS1 vérifié ; 0088"),
            new Field("buyer", "BT-55", "Pays", "buyer.address.countryCode", "invoice.buyer.address.countryCode",
                    BUYER + "/cac:PostalAddress/cac:Country/cbc:IdentificationCode", Kind.DIRECT, "ISO 3166-1"),

            new Field("line", "BT-126", "N° de ligne", "lines[{i}].id", "invoice.lines[{i}].id",
                    LINE + "/cbc:ID", Kind.DIRECT, "Copié"),
            new Field("line", "BT-129", "Quantité", "lines[{i}].quantity", "invoice.lines[{i}].quantity",
                    LINE + "/cbc:InvoicedQuantity", Kind.DIRECT, "Zéros non significatifs retirés"),
            new Field("line", "BT-130", "Unité", "lines[{i}].unitCode", "invoice.lines[{i}].unitCode",
                    LINE + "/cbc:InvoicedQuantity/@unitCode", Kind.DIRECT, "UN/ECE Rec. 20"),
            new Field("line", "BT-146", "Prix unitaire net", "lines[{i}].unitPrice", "invoice.lines[{i}].unitPrice",
                    LINE + "/cac:Price/cbc:PriceAmount", Kind.DIRECT, "Zéros non significatifs retirés"),
            new Field("line", "BT-153", "Désignation", "lines[{i}].itemName", "invoice.lines[{i}].itemName",
                    LINE + "/cac:Item/cbc:Name", Kind.DIRECT, "Copiée"),
            new Field("line", "BT-157", "GTIN", "lines[{i}].gtin", "invoice.lines[{i}].gtin",
                    LINE + "/cac:Item/cac:StandardItemIdentification/cbc:ID", Kind.DIRECT,
                    "Chiffre de contrôle GS1 vérifié ; schemeID 0160 ; omis si absent"),
            new Field("line", "BT-151", "Catégorie de TVA", "lines[{i}].vat.category",
                    "invoice.lines[{i}].vat.code", LINE + "/cac:Item/cac:ClassifiedTaxCategory/cbc:ID",
                    Kind.DIRECT, "Code UNTDID 5305"),
            new Field("line", "BT-152", "Taux de TVA", "lines[{i}].vat.ratePercent",
                    "invoice.lines[{i}].vat.ratePercent", LINE + "/cac:Item/cac:ClassifiedTaxCategory/cbc:Percent",
                    Kind.DIRECT, "Fourni par l'appelant en attendant le référentiel daté (TODO(DGI-SPEC))"),
            new Field("line", "BT-131", "Montant net de la ligne", null, null, LINE + "/cbc:LineExtensionAmount",
                    Kind.CALCULATED, "Quantité × prix, arrondi à la devise (mode d'arrondi configurable)"),

            new Field("totals", "BT-106", "Somme des lignes", null, null, TOTAL + "/cbc:LineExtensionAmount",
                    Kind.CALCULATED, "Somme des montants nets de ligne"),
            new Field("totals", "BT-109", "Total HT", null, null, TOTAL + "/cbc:TaxExclusiveAmount",
                    Kind.CALCULATED, "Somme des lignes (pas encore de remises ni de frais)"),
            new Field("totals", "BT-110", "Total TVA", null, null, "/inv:Invoice/cac:TaxTotal/cbc:TaxAmount",
                    Kind.CALCULATED, "TVA calculée une fois par catégorie et taux, puis additionnée"),
            new Field("totals", "BT-112", "Total TTC", null, null, TOTAL + "/cbc:TaxInclusiveAmount",
                    Kind.CALCULATED, "Total HT + total TVA"),
            new Field("totals", "BT-115", "Montant à payer", null, null, TOTAL + "/cbc:PayableAmount",
                    Kind.CALCULATED, "Total TTC (pas encore d'acomptes)")
    );

    private UblMappingSpec() {
    }
}
