package ma.mystix.invoice;

import java.util.ArrayList;
import java.util.Currency;
import java.util.List;
import java.util.function.Supplier;

import ma.mystix.canonical.Invoice;
import ma.mystix.canonical.InvoiceLine;
import ma.mystix.canonical.InvoiceTypeCode;
import ma.mystix.canonical.Party;
import ma.mystix.canonical.PostalAddress;
import ma.mystix.canonical.VatCategory;
import ma.mystix.canonical.VatCategoryCode;
import ma.mystix.shared.error.ApiError;
import ma.mystix.shared.error.ErrorCode;
import ma.mystix.shared.error.MystixException;

/**
 * Maps the JSON request to the canonical model. Canonical invariants (GS1 check digits, dates, amounts) are
 * reported with the JSON path of the offending field; all violations are collected, not only the first one.
 */
final class InvoiceRequestMapper {

    private final List<ApiError.FieldViolation> violations = new ArrayList<>();

    static Invoice toCanonical(InvoiceRequest request) {
        return new InvoiceRequestMapper().map(request);
    }

    private Invoice map(InvoiceRequest r) {
        Currency currency = at("currency", () -> Currency.getInstance(r.currency()));
        Party seller = party("seller", r.seller());
        Party buyer = party("buyer", r.buyer());
        List<InvoiceLine> lines = new ArrayList<>();
        for (int i = 0; i < r.lines().size(); i++) {
            InvoiceRequest.LineRequest line = r.lines().get(i);
            lines.add(at("lines[" + i + "]", () -> new InvoiceLine(line.id(), line.quantity(), line.unitCode(),
                    line.unitPrice(), line.itemName(), line.gtin(),
                    new VatCategory(VatCategoryCode.valueOf(line.vat().category()), line.vat().ratePercent()))));
        }
        if (!violations.isEmpty()) {
            throw rejected();
        }
        Invoice invoice = at("invoice", () -> new Invoice(r.number(), r.issueDate(),
                InvoiceTypeCode.COMMERCIAL_INVOICE, currency, r.dueDate(), r.buyerReference(),
                r.purchaseOrderReference(), r.note(), seller, buyer, lines));
        if (!violations.isEmpty()) {
            throw rejected();
        }
        return invoice;
    }

    private Party party(String path, InvoiceRequest.PartyRequest p) {
        return at(path, () -> new Party(p.name(), p.ice(), p.taxIdentifier(), p.tradeRegister(), p.gln(),
                new PostalAddress(p.address().street(), p.address().city(), p.address().postalCode(),
                        p.address().countryCode())));
    }

    private <T> T at(String path, Supplier<T> factory) {
        try {
            return factory.get();
        } catch (IllegalArgumentException e) {
            violations.add(new ApiError.FieldViolation(path, e.getMessage()));
            return null;
        }
    }

    private MystixException rejected() {
        return new MystixException(ErrorCode.INVOICE_REJECTED, "Invoice rejected by canonical mapping",
                violations, List.of(), null);
    }
}
