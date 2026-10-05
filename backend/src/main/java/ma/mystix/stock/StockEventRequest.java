package ma.mystix.stock;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * A canonical stock event (ADR-0011). EDIFACT parsers (DESADV, RECADV, ORDERS, INVRPT, Lot 6) will produce it.
 *
 * @param idempotencyKey  same key and same content: same result; same key, other content: refused
 * @param documentNumber  number of the source document (despatch advice, order, inventory report)
 * @param referenceNumber for a RECEIPT, the despatch advice it answers
 * @param occurredAt      when it happened in the warehouse; for a SNAPSHOT, the instant of the photograph
 */
record StockEventRequest(
        @NotBlank @Pattern(regexp = "^(SHIPMENT_NOTICE_IN|RECEIPT|ORDER|SHIPMENT_OUT|STATUS_CHANGE|ADJUSTMENT|SNAPSHOT)$")
        String type,
        @NotBlank @Size(max = 100) String idempotencyKey,
        @Size(max = 100) String documentNumber,
        @Size(max = 100) String referenceNumber,
        @NotNull OffsetDateTime occurredAt,
        @NotEmpty @Size(max = 10000) List<@NotNull @Valid Line> lines) {

    /**
     * One line. Which quantities are read depends on the event type:
     * <ul>
     *   <li>SHIPMENT_NOTICE_IN, ORDER, SHIPMENT_OUT: {@code quantity} (positive);</li>
     *   <li>RECEIPT: {@code accepted}, {@code refused}, {@code missing} (zero or positive);</li>
     *   <li>STATUS_CHANGE: {@code quantity}, {@code fromState}, {@code toState};</li>
     *   <li>ADJUSTMENT: {@code state} and a signed {@code quantity} (negative for a loss);</li>
     *   <li>SNAPSHOT: {@code state} and the reported {@code quantity} (zero or positive).</li>
     * </ul>
     */
    record Line(@NotBlank @Size(max = 64) String sku, @NotBlank @Size(max = 64) String location,
                BigDecimal quantity, StockState state, StockState fromState, StockState toState,
                BigDecimal accepted, BigDecimal refused, BigDecimal missing) {
    }
}
