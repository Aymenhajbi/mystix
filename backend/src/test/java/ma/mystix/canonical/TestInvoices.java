package ma.mystix.canonical;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Currency;
import java.util.List;

/** Synthetic invoices for tests. No real company, identifier or amount. VAT rates are test inputs. */
public final class TestInvoices {

    public static final VatCategory VAT_S_20 = new VatCategory(VatCategoryCode.S, new BigDecimal("20"));
    public static final VatCategory VAT_S_10 = new VatCategory(VatCategoryCode.S, new BigDecimal("10.00"));

    private TestInvoices() {
    }

    /** Three lines, two VAT rates, one rounding case (2.5 x 48.333 = 120.8325). */
    public static Invoice basic() {
        Party seller = new Party("Fournisseur Synthetique SARL", "000000001000011", "99999999", "RC-TEST-1",
                "6110000000017", new PostalAddress("12 Rue des Tests", "Casablanca", "20000", "MA"));
        Party buyer = new Party("Client Synthetique SA", "000000002000022", null, null,
                "6110000000024", new PostalAddress(null, "Rabat", null, "MA"));
        return new Invoice("FA-2026-000123", LocalDate.of(2026, 9, 15), InvoiceTypeCode.COMMERCIAL_INVOICE,
                Currency.getInstance("MAD"), LocalDate.of(2026, 10, 15), "ACHATS-42", "PO-7781",
                "Synthetic test invoice & fixture", seller, buyer,
                List.of(
                        new InvoiceLine("1", new BigDecimal("3"), "C62", new BigDecimal("125.50"),
                                "Carton test A", "6110000000109", VAT_S_20),
                        new InvoiceLine("2", new BigDecimal("2.5"), "KGM", new BigDecimal("48.333"),
                                "Produit au poids test B", null, VAT_S_20),
                        new InvoiceLine("3", BigDecimal.ONE, "C62", new BigDecimal("900"),
                                "Prestation test C", null, VAT_S_10)));
    }
}
