package ma.mystix.stock;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Stock of the client environment named by {@code X-Mystix-Company-Id} (ADR-0011).
 * TODO(auth): company from the authenticated principal instead of the header (Lot 8).
 */
@RestController
@RequestMapping("/api/v1/stock")
class StockController {

    private static final String COMPANY = "X-Mystix-Company-Id";
    private static final int MAX_LIMIT = 500;

    private final StockService stock;
    private final StockRepository repository;

    StockController(StockService stock, StockRepository repository) {
        this.stock = stock;
        this.repository = repository;
    }

    /** 201 when applied, 200 when the same event (same idempotency key and content) was already applied. */
    @PostMapping(value = "/events", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<StockService.Result> apply(@RequestHeader(COMPANY) UUID companyId,
                                              @Valid @RequestBody StockEventRequest request) {
        StockService.Result result = stock.apply(companyId, request);
        return ResponseEntity.status(result.replayed() ? 200 : 201).body(result);
    }

    /**
     * Stock of one item at one location: the five states, on-hand (available + reserved + quarantine) and
     * available to promise (available + in transit; reserved stock has already left available).
     */
    record Position(String sku, String location, Map<StockState, BigDecimal> states, BigDecimal onHand,
                    BigDecimal availableToPromise, OffsetDateTime updatedAt) {
    }

    @GetMapping(value = "/positions", produces = MediaType.APPLICATION_JSON_VALUE)
    List<Position> positions(@RequestHeader(COMPANY) UUID companyId, @RequestParam(required = false) String sku,
                             @RequestParam(required = false) String location) {
        Map<String, List<StockRepository.PositionRow>> grouped = new LinkedHashMap<>();
        for (StockRepository.PositionRow row : repository.positions(companyId, blank(sku), blank(location))) {
            grouped.computeIfAbsent(row.sku() + "\u0000" + row.location(), k -> new ArrayList<>()).add(row);
        }
        List<Position> positions = new ArrayList<>();
        for (List<StockRepository.PositionRow> rows : grouped.values()) {
            Map<StockState, BigDecimal> states = new EnumMap<>(StockState.class);
            for (StockState s : StockState.values()) {
                states.put(s, BigDecimal.ZERO.setScale(3));
            }
            OffsetDateTime updated = null;
            for (StockRepository.PositionRow row : rows) {
                states.put(row.state(), row.quantity());
                updated = updated == null || row.updatedAt().isAfter(updated) ? row.updatedAt() : updated;
            }
            BigDecimal onHand = states.get(StockState.AVAILABLE).add(states.get(StockState.RESERVED))
                    .add(states.get(StockState.QUARANTINE));
            BigDecimal atp = states.get(StockState.AVAILABLE).add(states.get(StockState.IN_TRANSIT));
            positions.add(new Position(rows.getFirst().sku(), rows.getFirst().location(), states, onHand, atp, updated));
        }
        return positions;
    }

    @GetMapping(value = "/movements", produces = MediaType.APPLICATION_JSON_VALUE)
    List<StockRepository.MovementRow> movements(@RequestHeader(COMPANY) UUID companyId,
                                                @RequestParam(required = false) String sku,
                                                @RequestParam(required = false) String location,
                                                @RequestParam(defaultValue = "100") int limit) {
        return repository.movements(companyId, blank(sku), blank(location), Math.clamp(limit, 1, MAX_LIMIT));
    }

    @GetMapping(value = "/alerts", produces = MediaType.APPLICATION_JSON_VALUE)
    List<StockRepository.AlertRow> alerts(@RequestHeader(COMPANY) UUID companyId,
                                          @RequestParam(defaultValue = "100") int limit) {
        return repository.alerts(companyId, Math.clamp(limit, 1, MAX_LIMIT));
    }

    private static String blank(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
