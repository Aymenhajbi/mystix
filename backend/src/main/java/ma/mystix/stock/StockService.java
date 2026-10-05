package ma.mystix.stock;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import ma.mystix.shared.Sha256;
import ma.mystix.shared.error.ApiError;
import ma.mystix.shared.error.ErrorCode;
import ma.mystix.shared.error.MystixException;
import ma.mystix.tenant.CompanyService;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * Applies canonical stock events to the movement ledger and the positions (ADR-0011). One event, one transaction:
 * an event that cannot be applied in full (insufficient stock) leaves no trace.
 */
@Service
public class StockService {

    private final StockRepository repository;
    private final CompanyService companies;
    private final JsonMapper json;
    private final Clock clock;

    StockService(StockRepository repository, CompanyService companies, JsonMapper json, Clock clock) {
        this.repository = repository;
        this.companies = companies;
        this.json = json;
        this.clock = clock;
    }

    /**
     * What an event did.
     *
     * @param linesCompared SNAPSHOT only: item/state lines compared with the ledger
     * @param linesMatched  SNAPSHOT only: lines without variance; accuracy = matched / compared
     */
    public record Result(UUID eventId, String type, boolean replayed, int movements, int variances, int alerts,
                         Integer linesCompared, Integer linesMatched) {
    }

    @Transactional
    public Result apply(UUID companyId, StockEventRequest request) {
        companies.get(companyId);
        validate(request);
        String sha = Sha256.hex(json.writeValueAsBytes(request));
        var existing = repository.findEvent(companyId, request.idempotencyKey());
        if (existing.isPresent()) {
            return replay(companyId, existing.get(), sha);
        }
        UUID eventId = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now(clock);
        try {
            repository.insertEvent(eventId, companyId, request, sha, now);
        } catch (DuplicateKeyException e) {
            // A concurrent request with the same key won the race.
            return replay(companyId, repository.findEvent(companyId, request.idempotencyKey()).orElseThrow(() -> e), sha);
        }
        Applier applier = new Applier(companyId, eventId, request.occurredAt(), now);
        switch (request.type()) {
            case "SHIPMENT_NOTICE_IN" -> request.lines().forEach(l -> applier.move(l, null, StockState.IN_TRANSIT, l.quantity()));
            case "RECEIPT" -> request.lines().forEach(l -> receipt(applier, request.referenceNumber(), l));
            case "ORDER" -> request.lines().forEach(l -> applier.move(l, StockState.AVAILABLE, StockState.RESERVED, l.quantity()));
            case "SHIPMENT_OUT" -> request.lines().forEach(l -> applier.move(l, StockState.RESERVED, null, l.quantity()));
            case "STATUS_CHANGE" -> request.lines().forEach(l -> applier.move(l, l.fromState(), l.toState(), l.quantity()));
            case "ADJUSTMENT" -> request.lines().forEach(l -> {
                if (l.quantity().signum() > 0) {
                    applier.move(l, null, l.state(), l.quantity());
                } else {
                    applier.move(l, l.state(), null, l.quantity().negate());
                }
            });
            case "SNAPSHOT" -> {
                int[] counts = reconcile(applier, request);
                repository.recordAccuracy(companyId, eventId, counts[0], counts[1]);
                return new Result(eventId, request.type(), false, applier.movements, applier.variances, applier.alerts,
                        counts[0], counts[1]);
            }
            default -> throw new IllegalStateException("Unknown stock event " + request.type());
        }
        return new Result(eventId, request.type(), false, applier.movements, applier.variances, applier.alerts, null,
                null);
    }

    /**
     * RECADV against its DESADV: accepted goes to AVAILABLE, refused to QUARANTINE, missing leaves the stock; all
     * taken from what the despatch advice still has in transit. A total that differs from what was still expected
     * raises a supplier dispute alert; a quantity beyond it enters directly (it never drives the transit negative).
     */
    private void receipt(Applier applier, String despatchAdvice, StockEventRequest.Line l) {
        BigDecimal despatched = repository.despatched(applier.companyId, despatchAdvice, l.sku(), l.location());
        BigDecimal remaining = despatched.subtract(
                repository.received(applier.companyId, despatchAdvice, l.sku(), l.location())).max(BigDecimal.ZERO);
        BigDecimal accepted = zeroIfNull(l.accepted());
        BigDecimal refused = zeroIfNull(l.refused());
        BigDecimal missing = zeroIfNull(l.missing());
        BigDecimal total = accepted.add(refused).add(missing);
        if (total.compareTo(remaining) != 0 || missing.signum() > 0 || refused.signum() > 0) {
            String why = despatched.signum() == 0
                    ? "no despatch advice " + despatchAdvice + " for this item and location"
                    : "despatch advice " + despatchAdvice + " still expected " + remaining.toPlainString()
                    + ", receipt accounts for " + total.toPlainString() + " (accepted " + accepted.toPlainString()
                    + ", refused " + refused.toPlainString() + ", missing " + missing.toPlainString() + ")";
            // expected = what the despatch advice still announced, actual = what was accepted into stock.
            repository.insertAlert(applier.companyId, applier.eventId, "RECEIPT_DISCREPANCY", l.sku(), l.location(),
                    remaining, accepted, "Supplier dispute: " + why, applier.now);
            applier.alerts++;
        }
        BigDecimal[] budget = {remaining};
        receiptPart(applier, l, accepted, StockState.AVAILABLE, budget);
        receiptPart(applier, l, refused, StockState.QUARANTINE, budget);
        // Missing goods leave the transit; nothing enters for them.
        BigDecimal missingFromTransit = missing.min(budget[0]);
        if (missingFromTransit.signum() > 0) {
            applier.move(l, StockState.IN_TRANSIT, null, missingFromTransit);
            budget[0] = budget[0].subtract(missingFromTransit);
        }
    }

    private void receiptPart(Applier applier, StockEventRequest.Line l, BigDecimal quantity, StockState to,
                             BigDecimal[] budget) {
        if (quantity.signum() == 0) {
            return;
        }
        BigDecimal fromTransit = quantity.min(budget[0]);
        if (fromTransit.signum() > 0) {
            applier.move(l, StockState.IN_TRANSIT, to, fromTransit);
            budget[0] = budget[0].subtract(fromTransit);
        }
        BigDecimal beyond = quantity.subtract(fromTransit);
        if (beyond.signum() > 0) {
            applier.move(l, null, to, beyond);
        }
    }

    /**
     * INVRPT snapshot: for each location of the snapshot, Δ = reported − ledger balance at {@code asOf}. Movements
     * dated after {@code asOf} (events integrated in between) are neither overwritten nor counted twice. A
     * difference becomes a variance, an alert, and a corrective movement dated {@code asOf}, so the warehouse value
     * becomes the reference. Item/state pairs of a covered location missing from the snapshot are reported as 0.
     */
    private int[] reconcile(Applier applier, StockEventRequest request) {
        OffsetDateTime asOf = request.occurredAt();
        Map<String, Map<StockRepository.Key, BigDecimal>> reported = new LinkedHashMap<>();
        for (StockEventRequest.Line l : request.lines()) {
            reported.computeIfAbsent(l.location(), k -> new LinkedHashMap<>())
                    .merge(new StockRepository.Key(l.sku(), l.state()), l.quantity(), BigDecimal::add);
        }
        int compared = 0;
        int matched = 0;
        for (var location : reported.entrySet()) {
            Map<StockRepository.Key, BigDecimal> expected = repository.balancesAsOf(applier.companyId, location.getKey(), asOf);
            Set<StockRepository.Key> keys = new LinkedHashSet<>(location.getValue().keySet());
            expected.forEach((k, v) -> {
                if (v.signum() != 0) {
                    keys.add(k);
                }
            });
            for (StockRepository.Key key : keys) {
                BigDecimal got = location.getValue().getOrDefault(key, BigDecimal.ZERO);
                BigDecimal want = expected.getOrDefault(key, BigDecimal.ZERO);
                compared++;
                BigDecimal delta = got.subtract(want);
                if (delta.signum() == 0) {
                    matched++;
                    continue;
                }
                repository.insertVariance(applier.companyId, applier.eventId, key.sku(), location.getKey(), key.state(),
                        got, want, asOf, applier.now);
                applier.variances++;
                repository.insertAlert(applier.companyId, applier.eventId, "INVENTORY_VARIANCE", key.sku(),
                        location.getKey(), want, got, "Inventory variance on " + key.state() + ": warehouse reports "
                                + got.toPlainString() + ", ledger had " + want.toPlainString() + " at " + asOf
                                + "; warehouse value applied", applier.now);
                applier.alerts++;
                StockEventRequest.Line line = new StockEventRequest.Line(key.sku(), location.getKey(), null, key.state(),
                        null, null, null, null, null);
                if (delta.signum() > 0) {
                    applier.record(line, null, key.state(), delta);
                    repository.adjust(applier.companyId, key.sku(), location.getKey(), key.state(), delta, applier.now);
                } else {
                    applier.record(line, key.state(), null, delta.negate());
                    repository.adjust(applier.companyId, key.sku(), location.getKey(), key.state(), delta, applier.now);
                }
            }
        }
        return new int[] {compared, matched};
    }

    private Result replay(UUID companyId, StockRepository.EventRow event, String sha) {
        if (!event.payloadSha256().equals(sha)) {
            throw new MystixException(ErrorCode.STOCK_EVENT_CONFLICT,
                    "Idempotency key already used with different content (event " + event.id() + ")");
        }
        return new Result(event.id(), event.type(), true, repository.count("stock_movement", companyId, event.id()),
                repository.count("stock_variance", companyId, event.id()),
                repository.count("stock_alert", companyId, event.id()), event.linesCompared(), event.linesMatched());
    }

    /** Per-type checks the bean annotations cannot express; all problems reported at once. */
    private static void validate(StockEventRequest r) {
        List<ApiError.FieldViolation> v = new ArrayList<>();
        for (int i = 0; i < r.lines().size(); i++) {
            StockEventRequest.Line l = r.lines().get(i);
            String p = "lines[" + i + "].";
            switch (r.type()) {
                case "SHIPMENT_NOTICE_IN", "ORDER", "SHIPMENT_OUT" -> positive(v, p + "quantity", l.quantity());
                case "RECEIPT" -> {
                    notNegative(v, p + "accepted", l.accepted());
                    notNegative(v, p + "refused", l.refused());
                    notNegative(v, p + "missing", l.missing());
                    if (zeroIfNull(l.accepted()).add(zeroIfNull(l.refused())).add(zeroIfNull(l.missing())).signum() == 0) {
                        v.add(new ApiError.FieldViolation(p + "accepted", "a receipt line needs accepted, refused or missing"));
                    }
                }
                case "STATUS_CHANGE" -> {
                    positive(v, p + "quantity", l.quantity());
                    if (l.fromState() == null || l.toState() == null || l.fromState() == l.toState()) {
                        v.add(new ApiError.FieldViolation(p + "toState", "fromState and toState must be two different states"));
                    }
                }
                case "ADJUSTMENT" -> {
                    required(v, p + "state", l.state());
                    if (l.quantity() == null || l.quantity().signum() == 0) {
                        v.add(new ApiError.FieldViolation(p + "quantity", "must be a non-zero signed quantity"));
                    }
                }
                case "SNAPSHOT" -> {
                    required(v, p + "state", l.state());
                    notNegative(v, p + "quantity", l.quantity());
                    required(v, p + "quantity", l.quantity());
                }
                default -> { }
            }
            for (BigDecimal q : new BigDecimal[] {l.quantity(), l.accepted(), l.refused(), l.missing()}) {
                if (q != null && q.stripTrailingZeros().scale() > 3) {
                    v.add(new ApiError.FieldViolation(p + "quantity", "3 decimals at most"));
                    break;
                }
            }
        }
        if ("RECEIPT".equals(r.type()) && (r.referenceNumber() == null || r.referenceNumber().isBlank())) {
            v.add(new ApiError.FieldViolation("referenceNumber", "a receipt must name the despatch advice it answers"));
        }
        if ("SHIPMENT_NOTICE_IN".equals(r.type()) && (r.documentNumber() == null || r.documentNumber().isBlank())) {
            v.add(new ApiError.FieldViolation("documentNumber", "a despatch advice needs its number"));
        }
        if (!v.isEmpty()) {
            throw new MystixException(ErrorCode.VALIDATION_FAILED, "Invalid stock event", v, List.of(), null);
        }
    }

    private static void positive(List<ApiError.FieldViolation> v, String path, BigDecimal q) {
        if (q == null || q.signum() <= 0) {
            v.add(new ApiError.FieldViolation(path, "must be a positive quantity"));
        }
    }

    private static void notNegative(List<ApiError.FieldViolation> v, String path, BigDecimal q) {
        if (q != null && q.signum() < 0) {
            v.add(new ApiError.FieldViolation(path, "must not be negative"));
        }
    }

    private static void required(List<ApiError.FieldViolation> v, String path, Object value) {
        if (value == null) {
            v.add(new ApiError.FieldViolation(path, "must not be null"));
        }
    }

    private static BigDecimal zeroIfNull(BigDecimal q) {
        return q == null ? BigDecimal.ZERO : q;
    }

    /** Writes the movements of one event and keeps the positions in step. */
    private final class Applier {
        final UUID companyId;
        final UUID eventId;
        final OffsetDateTime occurredAt;
        final OffsetDateTime now;
        int movements;
        int variances;
        int alerts;

        Applier(UUID companyId, UUID eventId, OffsetDateTime occurredAt, OffsetDateTime now) {
            this.companyId = companyId;
            this.eventId = eventId;
            this.occurredAt = occurredAt;
            this.now = now;
        }

        /** A movement whose source must hold the quantity (else the whole event is refused). */
        void move(StockEventRequest.Line l, StockState from, StockState to, BigDecimal quantity) {
            if (from != null && !repository.take(companyId, l.sku(), l.location(), from, quantity, now)) {
                BigDecimal held = repository.position(companyId, l.sku(), l.location(), from);
                throw new MystixException(ErrorCode.STOCK_INSUFFICIENT,
                        "Not enough " + from + " stock for " + l.sku() + " at " + l.location() + ": " + held.toPlainString()
                                + " held, " + quantity.toPlainString() + " requested",
                        List.of(new ApiError.FieldViolation("lines", l.sku() + " at " + l.location() + ": " + from
                                + " holds " + held.toPlainString() + ", " + quantity.toPlainString() + " requested")),
                        List.of(), null);
            }
            if (to != null) {
                repository.add(companyId, l.sku(), l.location(), to, quantity, now);
            }
            record(l, from, to, quantity);
        }

        void record(StockEventRequest.Line l, StockState from, StockState to, BigDecimal quantity) {
            repository.insertMovement(companyId, eventId, l.sku(), l.location(), from, to, quantity, occurredAt, now);
            movements++;
        }
    }
}
