package ma.mystix.format.ubl;

import java.math.BigDecimal;
import java.util.Objects;

import ma.mystix.canonical.Invoice;
import ma.mystix.canonical.InvoiceLine;
import ma.mystix.canonical.InvoiceTotals;
import ma.mystix.canonical.Party;
import ma.mystix.canonical.PostalAddress;
import ma.mystix.canonical.VatCategory;

/**
 * Generates a UBL 2.1 Invoice from the canonical model. Element order follows the UBL-Invoice-2.1 XSD sequence.
 * The output is deterministic: same canonical input and settings, same bytes.
 */
public final class UblInvoiceGenerator {

    private static final String NS_INVOICE = "urn:oasis:names:specification:ubl:schema:xsd:Invoice-2";
    private static final String NS_CAC = "urn:oasis:names:specification:ubl:schema:xsd:CommonAggregateComponents-2";
    private static final String NS_CBC = "urn:oasis:names:specification:ubl:schema:xsd:CommonBasicComponents-2";
    /** ISO 6523 ICD 0088: GS1 Global Location Number. */
    private static final String SCHEME_GLN = "0088";
    /** ISO 6523 ICD 0160: GS1 Global Trade Item Number. */
    private static final String SCHEME_GTIN = "0160";
    private static final String VAT_SCHEME = "VAT";

    private final UblSettings settings;

    public UblInvoiceGenerator(UblSettings settings) {
        this.settings = Objects.requireNonNull(settings, "settings");
    }

    public byte[] generate(Invoice invoice, InvoiceTotals totals) {
        if (totals.lineNetAmounts().size() != invoice.lines().size()) {
            throw new IllegalArgumentException("Totals were not computed for this invoice");
        }
        String currency = invoice.currency().getCurrencyCode();
        XmlWriter xml = new XmlWriter();

        xml.start("Invoice", "xmlns", NS_INVOICE, "xmlns:cac", NS_CAC, "xmlns:cbc", NS_CBC);
        xml.element("cbc:UBLVersionID", "2.1");
        xml.element("cbc:CustomizationID", settings.customizationId());
        xml.element("cbc:ID", invoice.number());
        xml.element("cbc:IssueDate", invoice.issueDate().toString());
        xml.element("cbc:DueDate", invoice.dueDate() == null ? null : invoice.dueDate().toString());
        xml.element("cbc:InvoiceTypeCode", invoice.typeCode().code());
        xml.element("cbc:Note", invoice.note());
        xml.element("cbc:DocumentCurrencyCode", currency);
        xml.element("cbc:BuyerReference", invoice.buyerReference());
        if (invoice.purchaseOrderReference() != null) {
            xml.start("cac:OrderReference").element("cbc:ID", invoice.purchaseOrderReference()).end();
        }

        xml.start("cac:AccountingSupplierParty");
        writeParty(xml, invoice.seller(), true);
        xml.end();
        xml.start("cac:AccountingCustomerParty");
        writeParty(xml, invoice.buyer(), false);
        xml.end();

        xml.start("cac:TaxTotal");
        xml.element("cbc:TaxAmount", amount(totals.taxAmount()), "currencyID", currency);
        for (InvoiceTotals.VatBreakdown vat : totals.vatBreakdown()) {
            xml.start("cac:TaxSubtotal");
            xml.element("cbc:TaxableAmount", amount(vat.taxableAmount()), "currencyID", currency);
            xml.element("cbc:TaxAmount", amount(vat.taxAmount()), "currencyID", currency);
            writeTaxCategory(xml, "cac:TaxCategory", vat.category());
            xml.end();
        }
        xml.end();

        xml.start("cac:LegalMonetaryTotal");
        xml.element("cbc:LineExtensionAmount", amount(totals.lineExtensionAmount()), "currencyID", currency);
        xml.element("cbc:TaxExclusiveAmount", amount(totals.taxExclusiveAmount()), "currencyID", currency);
        xml.element("cbc:TaxInclusiveAmount", amount(totals.taxInclusiveAmount()), "currencyID", currency);
        xml.element("cbc:PayableAmount", amount(totals.payableAmount()), "currencyID", currency);
        xml.end();

        for (int i = 0; i < invoice.lines().size(); i++) {
            writeLine(xml, invoice.lines().get(i), totals.lineNetAmounts().get(i), currency);
        }

        xml.end();
        return xml.toBytes();
    }

    private void writeParty(XmlWriter xml, Party party, boolean seller) {
        xml.start("cac:Party");
        if (party.gln() != null) {
            xml.start("cac:PartyIdentification").element("cbc:ID", party.gln(), "schemeID", SCHEME_GLN).end();
        }
        writeAddress(xml, party.address());
        if (seller && party.taxIdentifier() != null) {
            xml.start("cac:PartyTaxScheme");
            xml.element("cbc:CompanyID", party.taxIdentifier());
            xml.start("cac:TaxScheme").element("cbc:ID", settings.taxIdentifierSchemeId()).end();
            xml.end();
        }
        xml.start("cac:PartyLegalEntity");
        xml.element("cbc:RegistrationName", party.name());
        xml.element("cbc:CompanyID", party.ice(), "schemeID", settings.iceSchemeId());
        xml.end();
        xml.end();
    }

    private static void writeAddress(XmlWriter xml, PostalAddress address) {
        xml.start("cac:PostalAddress");
        xml.element("cbc:StreetName", address.street());
        xml.element("cbc:CityName", address.city());
        xml.element("cbc:PostalZone", address.postalCode());
        xml.start("cac:Country").element("cbc:IdentificationCode", address.countryCode()).end();
        xml.end();
    }

    private static void writeLine(XmlWriter xml, InvoiceLine line, BigDecimal netAmount, String currency) {
        xml.start("cac:InvoiceLine");
        xml.element("cbc:ID", line.id());
        xml.element("cbc:InvoicedQuantity", decimal(line.quantity()), "unitCode", line.unitCode());
        xml.element("cbc:LineExtensionAmount", amount(netAmount), "currencyID", currency);
        xml.start("cac:Item");
        xml.element("cbc:Name", line.itemName());
        if (line.gtin() != null) {
            xml.start("cac:StandardItemIdentification")
                    .element("cbc:ID", line.gtin(), "schemeID", SCHEME_GTIN)
                    .end();
        }
        writeTaxCategory(xml, "cac:ClassifiedTaxCategory", line.vat());
        xml.end();
        xml.start("cac:Price").element("cbc:PriceAmount", decimal(line.unitPrice()), "currencyID", currency).end();
        xml.end();
    }

    private static void writeTaxCategory(XmlWriter xml, String element, VatCategory category) {
        xml.start(element);
        xml.element("cbc:ID", category.code().name());
        xml.element("cbc:Percent", decimal(category.ratePercent()));
        xml.start("cac:TaxScheme").element("cbc:ID", VAT_SCHEME).end();
        xml.end();
    }

    private static String amount(BigDecimal value) {
        return value.toPlainString();
    }

    private static String decimal(BigDecimal value) {
        BigDecimal stripped = value.stripTrailingZeros();
        return (stripped.scale() < 0 ? stripped.setScale(0) : stripped).toPlainString();
    }
}
