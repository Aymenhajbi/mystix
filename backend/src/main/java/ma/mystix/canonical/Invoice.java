package ma.mystix.canonical;

import java.time.LocalDate;
import java.util.Currency;
import java.util.List;
import java.util.Objects;

/**
 * Canonical invoice, aligned with EN 16931 and extended for Morocco and GS1.
 *
 * @param number                 BT-1 invoice number
 * @param issueDate              BT-2 issue date
 * @param typeCode               BT-3 invoice type code
 * @param currency               BT-5 invoice currency (ISO 4217)
 * @param dueDate                BT-9 payment due date, optional
 * @param buyerReference         BT-10 buyer reference, optional
 * @param purchaseOrderReference BT-13 purchase order reference, optional
 * @param note                   BT-22 invoice note, optional
 * @param seller                 BG-4
 * @param buyer                  BG-7
 * @param lines                  BG-25, at least one
 */
public record Invoice(String number, LocalDate issueDate, InvoiceTypeCode typeCode, Currency currency,
                      LocalDate dueDate, String buyerReference, String purchaseOrderReference, String note,
                      Party seller, Party buyer, List<InvoiceLine> lines) {

    public Invoice {
        if (number == null || number.isBlank()) {
            throw new IllegalArgumentException("Invoice number is required");
        }
        Objects.requireNonNull(issueDate, "issueDate");
        Objects.requireNonNull(typeCode, "typeCode");
        Objects.requireNonNull(currency, "currency");
        Objects.requireNonNull(seller, "seller");
        Objects.requireNonNull(buyer, "buyer");
        if (lines == null || lines.isEmpty()) {
            throw new IllegalArgumentException("An invoice has at least one line");
        }
        lines = List.copyOf(lines);
        if (dueDate != null && dueDate.isBefore(issueDate)) {
            throw new IllegalArgumentException("Due date must not be before issue date");
        }
    }
}
