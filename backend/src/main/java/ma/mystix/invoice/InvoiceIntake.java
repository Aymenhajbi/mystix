package ma.mystix.invoice;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import ma.mystix.canonical.Invoice;
import ma.mystix.mapping.MappingRule;
import ma.mystix.mapping.RuleEngine;
import ma.mystix.referential.VatReferential;
import ma.mystix.shared.error.ApiError;
import ma.mystix.shared.error.ErrorCode;
import ma.mystix.shared.error.MystixException;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * Reading a submission: JSON → flow rules (ADR-0008) → field validation → canonical model.
 * Shared by real submissions and dry runs, so a test runs exactly what production runs.
 */
@Component
class InvoiceIntake {

    private final JsonMapper json;
    private final Validator validator;
    private final VatReferential vatRates;

    InvoiceIntake(JsonMapper json, Validator validator, VatReferential vatRates) {
        this.json = json;
        this.validator = validator;
        this.vatRates = vatRates;
    }

    /**
     * Per-company rules applied at intake.
     *
     * @param enforcedSellerIce the company ICE when the seller identity rule is on (ADR-0009), else {@code null}
     * @param checkVatRates     standard rates must be in force on the issue date (ADR-0010)
     */
    record Policy(String enforcedSellerIce, boolean checkVatRates) {
        /** Dry runs: flow rules only; stored samples already went through the company rules. */
        static final Policy NONE = new Policy(null, false);
    }

    Invoice read(byte[] body, List<MappingRule> rules) {
        return read(body, rules, Policy.NONE);
    }

    Invoice read(byte[] body, List<MappingRule> rules, Policy policy) {
        InvoiceRequest request = validated(sellerIdentity(applyRules(parse(body), rules), policy.enforcedSellerIce()));
        if (policy.checkVatRates()) {
            checkVatRates(request);
        }
        return InvoiceRequestMapper.toCanonical(request);
    }

    /**
     * Each standard-rated (S) line carries a rate in force on the issue date in the seller's country. When the
     * referential has no rate for that country and date, nothing is checked: an empty referential never blocks.
     */
    void checkVatRates(InvoiceRequest r) {
        String country = r.seller().address().countryCode();
        Optional<List<BigDecimal>> inForce = vatRates.standardRates(country, r.issueDate());
        if (inForce.isEmpty()) {
            return;
        }
        List<BigDecimal> rates = inForce.get();
        List<ApiError.FieldViolation> violations = new ArrayList<>();
        for (int i = 0; i < r.lines().size(); i++) {
            InvoiceRequest.VatRequest vat = r.lines().get(i).vat();
            if ("S".equals(vat.category()) && rates.stream().noneMatch(x -> x.compareTo(vat.ratePercent()) == 0)) {
                violations.add(new ApiError.FieldViolation("lines[" + i + "].vat.ratePercent",
                        vat.ratePercent().stripTrailingZeros().toPlainString() + " % is not a standard VAT rate in force on "
                                + r.issueDate() + " in " + country + " (in force: "
                                + rates.stream().map(x -> x.stripTrailingZeros().toPlainString()).collect(Collectors.joining(", "))
                                + ")"));
            }
        }
        if (!violations.isEmpty()) {
            throw new MystixException(ErrorCode.VAT_RATE_UNKNOWN, "VAT rate not in force on " + r.issueDate(),
                    violations, List.of(), null);
        }
    }

    /**
     * Seller identity rule (per company, on by default): the seller is the submitting company. A missing seller
     * ICE is completed with the company ICE; a different one is rejected rather than silently replaced.
     */
    InvoiceRequest sellerIdentity(InvoiceRequest r, String enforcedSellerIce) {
        if (enforcedSellerIce == null || r.seller() == null) {
            return r;
        }
        InvoiceRequest.PartyRequest s = r.seller();
        if (s.ice() != null && !s.ice().isBlank()) {
            if (!s.ice().strip().equals(enforcedSellerIce)) {
                throw new MystixException(ErrorCode.SELLER_ICE_MISMATCH,
                        "Seller ICE " + s.ice() + " is not the company ICE " + enforcedSellerIce,
                        List.of(new ApiError.FieldViolation("seller.ice",
                                "must be the ICE of the submitting company (" + enforcedSellerIce + ")")),
                        List.of(), null);
            }
            return r;
        }
        InvoiceRequest.PartyRequest seller = new InvoiceRequest.PartyRequest(s.name(), enforcedSellerIce,
                s.taxIdentifier(), s.tradeRegister(), s.gln(), s.address());
        return new InvoiceRequest(r.number(), r.issueDate(), r.currency(), r.dueDate(), r.buyerReference(),
                r.purchaseOrderReference(), r.note(), seller, r.buyer(), r.lines());
    }

    /** Invoice number as received, for logs; {@code null} when the body cannot be read. */
    String numberOf(byte[] body) {
        try {
            InvoiceRequest r = json.readValue(body, InvoiceRequest.class);
            return r == null ? null : r.number();
        } catch (JacksonException e) {
            return null;
        }
    }

    InvoiceRequest parse(byte[] body) {
        InvoiceRequest request;
        try {
            request = json.readValue(body, InvoiceRequest.class);
        } catch (JacksonException e) {
            throw new MystixException(ErrorCode.VALIDATION_FAILED, "Unreadable invoice JSON: " + e.getOriginalMessage());
        }
        if (request == null) {
            throw new MystixException(ErrorCode.VALIDATION_FAILED, "Empty invoice body");
        }
        return request;
    }

    InvoiceRequest applyRules(InvoiceRequest r, List<MappingRule> rules) {
        if (rules == null || rules.isEmpty()) {
            return r;
        }
        RuleEngine.View out;
        try {
            out = RuleEngine.apply(view(r), rules);
        } catch (RuleEngine.Rejection e) {
            throw new MystixException(ErrorCode.MAPPING_RULE_REJECTED, "Flow rule rejected a value: " + e.getMessage(),
                    List.of(new ApiError.FieldViolation(e.field(), e.getMessage())), List.of(), e);
        }
        Map<String, String> h = out.header();
        List<InvoiceRequest.LineRequest> lines = r.lines() == null ? null : new ArrayList<>();
        for (int i = 0; lines != null && i < r.lines().size(); i++) {
            InvoiceRequest.LineRequest l = r.lines().get(i);
            Map<String, String> v = out.lines().get(i);
            lines.add(l == null ? null : new InvoiceRequest.LineRequest(l.id(), l.quantity(), v.get("unitCode"),
                    l.unitPrice(), v.get("itemName"), l.gtin(), l.vat()));
        }
        return new InvoiceRequest(r.number(), r.issueDate(), r.currency(), r.dueDate(), h.get("buyerReference"),
                h.get("purchaseOrderReference"), h.get("note"), withName(r.seller(), h.get("seller.name")),
                withName(r.buyer(), h.get("buyer.name")), lines);
    }

    InvoiceRequest validated(InvoiceRequest request) {
        Set<ConstraintViolation<InvoiceRequest>> violations = validator.validate(request);
        if (!violations.isEmpty()) {
            throw new MystixException(ErrorCode.VALIDATION_FAILED, "Invalid invoice request",
                    violations.stream()
                            .map(v -> new ApiError.FieldViolation(v.getPropertyPath().toString(), v.getMessage()))
                            .sorted(Comparator.comparing(ApiError.FieldViolation::field))
                            .toList(),
                    List.of(), null);
        }
        return request;
    }

    private static RuleEngine.View view(InvoiceRequest r) {
        Map<String, String> header = new LinkedHashMap<>();
        header.put("number", r.number());
        header.put("buyerReference", r.buyerReference());
        header.put("purchaseOrderReference", r.purchaseOrderReference());
        header.put("note", r.note());
        header.put("seller.name", r.seller() == null ? null : r.seller().name());
        header.put("buyer.name", r.buyer() == null ? null : r.buyer().name());
        List<Map<String, String>> lines = new ArrayList<>();
        if (r.lines() != null) {
            for (InvoiceRequest.LineRequest l : r.lines()) {
                Map<String, String> line = new LinkedHashMap<>();
                if (l != null) {
                    line.put("id", l.id());
                    line.put("unitCode", l.unitCode());
                    line.put("itemName", l.itemName());
                    line.put("gtin", l.gtin());
                }
                lines.add(line);
            }
        }
        return new RuleEngine.View(header, lines);
    }

    private static InvoiceRequest.PartyRequest withName(InvoiceRequest.PartyRequest p, String name) {
        return p == null ? null : new InvoiceRequest.PartyRequest(name, p.ice(), p.taxIdentifier(), p.tradeRegister(),
                p.gln(), p.address());
    }
}
