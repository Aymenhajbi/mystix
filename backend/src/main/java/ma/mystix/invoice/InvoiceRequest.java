package ma.mystix.invoice;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * JSON input of {@code POST /api/v1/invoices}. Field names follow the canonical model; BT numbers in
 * {@link ma.mystix.canonical.Invoice}.
 */
record InvoiceRequest(
        @NotBlank @Size(max = 100) String number,
        @NotNull LocalDate issueDate,
        @NotBlank @Pattern(regexp = "^[A-Z]{3}$", message = "must be an ISO 4217 code") String currency,
        LocalDate dueDate,
        @Size(max = 100) String buyerReference,
        @Size(max = 100) String purchaseOrderReference,
        @Size(max = 1000) String note,
        @NotNull @Valid PartyRequest seller,
        @NotNull @Valid PartyRequest buyer,
        @NotEmpty @Size(max = 10000) List<@NotNull @Valid LineRequest> lines) {

    record PartyRequest(
            @NotBlank @Size(max = 255) String name,
            @Pattern(regexp = "^[0-9]{15}$", message = "must be exactly 15 digits") String ice,
            @Size(max = 20) String taxIdentifier,
            @Size(max = 50) String tradeRegister,
            String gln,
            @NotNull @Valid AddressRequest address) {
    }

    record AddressRequest(
            @Size(max = 255) String street,
            @Size(max = 100) String city,
            @Size(max = 20) String postalCode,
            @NotBlank @Pattern(regexp = "^[A-Z]{2}$", message = "must be an ISO 3166-1 alpha-2 code")
            String countryCode) {
    }

    record LineRequest(
            @NotBlank @Size(max = 50) String id,
            @NotNull BigDecimal quantity,
            @NotBlank @Size(max = 3) String unitCode,
            @NotNull @DecimalMin("0") BigDecimal unitPrice,
            @NotBlank @Size(max = 255) String itemName,
            String gtin,
            @NotNull @Valid VatRequest vat) {
    }

    /**
     * VAT of a line. The rate is supplied by the caller until the dated VAT referential exists.
     * TODO(DGI-SPEC): resolve the rate from the referential and reject rates it does not know.
     */
    record VatRequest(
            @NotBlank @Pattern(regexp = "^(S|Z|E|O)$", message = "must be one of S, Z, E, O") String category,
            @NotNull @DecimalMin("0") @DecimalMax("100") BigDecimal ratePercent) {
    }
}
