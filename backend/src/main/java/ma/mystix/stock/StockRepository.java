package ma.mystix.stock;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Stock tables (ADR-0011). Every query is scoped by {@code company_id}. */
@Repository
class StockRepository {

    private final JdbcClient jdbc;

    StockRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    record EventRow(UUID id, String type, String payloadSha256, Integer linesCompared, Integer linesMatched) {
    }

    Optional<EventRow> findEvent(UUID companyId, String idempotencyKey) {
        return jdbc.sql("""
                        SELECT id, type, payload_sha256, lines_compared, lines_matched FROM stock_event
                        WHERE company_id = :c AND idempotency_key = :key
                        """)
                .param("c", companyId).param("key", idempotencyKey)
                .query((rs, n) -> new EventRow(rs.getObject("id", UUID.class), rs.getString("type"),
                        rs.getString("payload_sha256"), (Integer) rs.getObject("lines_compared"),
                        (Integer) rs.getObject("lines_matched")))
                .optional();
    }

    void insertEvent(UUID id, UUID companyId, StockEventRequest r, String sha256, OffsetDateTime recordedAt) {
        jdbc.sql("""
                        INSERT INTO stock_event (id, company_id, type, idempotency_key, payload_sha256, document_number,
                                                 reference_number, occurred_at, recorded_at)
                        VALUES (:id, :c, :type, :key, :sha, :doc, :ref, :occurred, :recorded)
                        """)
                .param("id", id).param("c", companyId).param("type", r.type()).param("key", r.idempotencyKey())
                .param("sha", sha256).param("doc", r.documentNumber()).param("ref", r.referenceNumber())
                .param("occurred", r.occurredAt()).param("recorded", recordedAt)
                .update();
    }

    void recordAccuracy(UUID companyId, UUID eventId, int compared, int matched) {
        jdbc.sql("UPDATE stock_event SET lines_compared = :compared, lines_matched = :matched WHERE company_id = :c AND id = :id")
                .param("compared", compared).param("matched", matched).param("c", companyId).param("id", eventId)
                .update();
    }

    void insertMovement(UUID companyId, UUID eventId, String sku, String location, StockState from, StockState to,
                        BigDecimal quantity, OffsetDateTime occurredAt, OffsetDateTime recordedAt) {
        jdbc.sql("""
                        INSERT INTO stock_movement (id, company_id, event_id, sku, location, from_state, to_state,
                                                    quantity, occurred_at, recorded_at)
                        VALUES (:id, :c, :event, :sku, :location, :from, :to, :q, :occurred, :recorded)
                        """)
                .param("id", UUID.randomUUID()).param("c", companyId).param("event", eventId).param("sku", sku)
                .param("location", location).param("from", from == null ? null : from.name())
                .param("to", to == null ? null : to.name()).param("q", quantity).param("occurred", occurredAt)
                .param("recorded", recordedAt)
                .update();
    }

    /** Adds to a position (atomic increment, the row is created on first use). */
    void add(UUID companyId, String sku, String location, StockState state, BigDecimal quantity, OffsetDateTime at) {
        jdbc.sql("""
                        INSERT INTO stock_position (company_id, sku, location, state, quantity, updated_at)
                        VALUES (:c, :sku, :location, :state, :q, :at)
                        ON CONFLICT (company_id, sku, location, state)
                        DO UPDATE SET quantity = stock_position.quantity + EXCLUDED.quantity, updated_at = EXCLUDED.updated_at
                        """)
                .param("c", companyId).param("sku", sku).param("location", location).param("state", state.name())
                .param("q", quantity).param("at", at)
                .update();
    }

    /** Takes from a position only if it holds enough (atomic, safe under concurrent events). */
    boolean take(UUID companyId, String sku, String location, StockState state, BigDecimal quantity, OffsetDateTime at) {
        return jdbc.sql("""
                        UPDATE stock_position SET quantity = quantity - :q, updated_at = :at
                        WHERE company_id = :c AND sku = :sku AND location = :location AND state = :state
                          AND quantity >= :q
                        """)
                .param("q", quantity).param("at", at).param("c", companyId).param("sku", sku)
                .param("location", location).param("state", state.name())
                .update() == 1;
    }

    /** Adds a signed quantity, even below zero: used only to align on an inventory snapshot. */
    void adjust(UUID companyId, String sku, String location, StockState state, BigDecimal delta, OffsetDateTime at) {
        add(companyId, sku, location, state, delta, at);
    }

    BigDecimal position(UUID companyId, String sku, String location, StockState state) {
        return jdbc.sql("""
                        SELECT quantity FROM stock_position
                        WHERE company_id = :c AND sku = :sku AND location = :location AND state = :state
                        """)
                .param("c", companyId).param("sku", sku).param("location", location).param("state", state.name())
                .query(BigDecimal.class).optional().orElse(BigDecimal.ZERO);
    }

    /** Quantity a despatch advice put in transit for an item at a location. */
    BigDecimal despatched(UUID companyId, String despatchAdvice, String sku, String location) {
        return jdbc.sql("""
                        SELECT COALESCE(SUM(m.quantity), 0) FROM stock_movement m
                        JOIN stock_event e ON e.id = m.event_id AND e.company_id = m.company_id
                        WHERE m.company_id = :c AND e.type = 'SHIPMENT_NOTICE_IN' AND e.document_number = :doc
                          AND m.sku = :sku AND m.location = :location AND m.to_state = 'IN_TRANSIT'
                        """)
                .param("c", companyId).param("doc", despatchAdvice).param("sku", sku).param("location", location)
                .query(BigDecimal.class).single();
    }

    /** Quantity already taken out of transit by earlier receipts of the same despatch advice. */
    BigDecimal received(UUID companyId, String despatchAdvice, String sku, String location) {
        return jdbc.sql("""
                        SELECT COALESCE(SUM(m.quantity), 0) FROM stock_movement m
                        JOIN stock_event e ON e.id = m.event_id AND e.company_id = m.company_id
                        WHERE m.company_id = :c AND e.type = 'RECEIPT' AND e.reference_number = :doc
                          AND m.sku = :sku AND m.location = :location AND m.from_state = 'IN_TRANSIT'
                        """)
                .param("c", companyId).param("doc", despatchAdvice).param("sku", sku).param("location", location)
                .query(BigDecimal.class).single();
    }

    record Key(String sku, StockState state) {
    }

    /** Balances per item and state at a location, from the movements in effect at {@code asOf} (inclusive). */
    Map<Key, BigDecimal> balancesAsOf(UUID companyId, String location, OffsetDateTime asOf) {
        Map<Key, BigDecimal> balances = new LinkedHashMap<>();
        jdbc.sql("""
                        SELECT sku, state, SUM(q) AS balance FROM (
                            SELECT sku, to_state AS state, quantity AS q FROM stock_movement
                            WHERE company_id = :c AND location = :location AND occurred_at <= :asOf AND to_state IS NOT NULL
                            UNION ALL
                            SELECT sku, from_state AS state, -quantity AS q FROM stock_movement
                            WHERE company_id = :c AND location = :location AND occurred_at <= :asOf AND from_state IS NOT NULL
                        ) moves
                        GROUP BY sku, state
                        ORDER BY sku, state
                        """)
                .param("c", companyId).param("location", location).param("asOf", asOf)
                .query((rs, n) -> balances.put(new Key(rs.getString("sku"), StockState.valueOf(rs.getString("state"))),
                        rs.getBigDecimal("balance")))
                .list();
        return balances;
    }

    void insertVariance(UUID companyId, UUID eventId, String sku, String location, StockState state,
                        BigDecimal reported, BigDecimal expected, OffsetDateTime asOf, OffsetDateTime at) {
        jdbc.sql("""
                        INSERT INTO stock_variance (id, company_id, event_id, sku, location, state, reported, expected,
                                                    delta, as_of, recorded_at)
                        VALUES (:id, :c, :event, :sku, :location, :state, :reported, :expected, :delta, :asOf, :at)
                        """)
                .param("id", UUID.randomUUID()).param("c", companyId).param("event", eventId).param("sku", sku)
                .param("location", location).param("state", state.name()).param("reported", reported)
                .param("expected", expected).param("delta", reported.subtract(expected)).param("asOf", asOf)
                .param("at", at)
                .update();
    }

    void insertAlert(UUID companyId, UUID eventId, String kind, String sku, String location, BigDecimal expected,
                     BigDecimal actual, String message, OffsetDateTime at) {
        jdbc.sql("""
                        INSERT INTO stock_alert (id, company_id, event_id, kind, sku, location, expected, actual, message,
                                                 recorded_at)
                        VALUES (:id, :c, :event, :kind, :sku, :location, :expected, :actual, :message, :at)
                        """)
                .param("id", UUID.randomUUID()).param("c", companyId).param("event", eventId).param("kind", kind)
                .param("sku", sku).param("location", location).param("expected", expected).param("actual", actual)
                .param("message", message.length() > 500 ? message.substring(0, 499) + "…" : message).param("at", at)
                .update();
    }

    int count(String table, UUID companyId, UUID eventId) {
        return jdbc.sql("SELECT COUNT(*) FROM " + table + " WHERE company_id = :c AND event_id = :e")
                .param("c", companyId).param("e", eventId).query(Integer.class).single();
    }

    record PositionRow(String sku, String location, StockState state, BigDecimal quantity, OffsetDateTime updatedAt) {
    }

    List<PositionRow> positions(UUID companyId, String sku, String location) {
        return jdbc.sql("""
                        SELECT sku, location, state, quantity, updated_at FROM stock_position
                        WHERE company_id = :c AND (CAST(:sku AS VARCHAR) IS NULL OR sku = :sku)
                          AND (CAST(:location AS VARCHAR) IS NULL OR location = :location)
                        ORDER BY sku, location, state
                        """)
                .param("c", companyId).param("sku", sku).param("location", location)
                .query((rs, n) -> new PositionRow(rs.getString("sku"), rs.getString("location"),
                        StockState.valueOf(rs.getString("state")), rs.getBigDecimal("quantity"),
                        rs.getObject("updated_at", OffsetDateTime.class)))
                .list();
    }

    record MovementRow(UUID eventId, String eventType, String documentNumber, String sku, String location,
                       StockState fromState, StockState toState, BigDecimal quantity, OffsetDateTime occurredAt) {
    }

    List<MovementRow> movements(UUID companyId, String sku, String location, int limit) {
        return jdbc.sql("""
                        SELECT m.event_id, e.type, e.document_number, m.sku, m.location, m.from_state, m.to_state,
                               m.quantity, m.occurred_at
                        FROM stock_movement m JOIN stock_event e ON e.id = m.event_id AND e.company_id = m.company_id
                        WHERE m.company_id = :c AND (CAST(:sku AS VARCHAR) IS NULL OR m.sku = :sku)
                          AND (CAST(:location AS VARCHAR) IS NULL OR m.location = :location)
                        ORDER BY m.occurred_at DESC, m.recorded_at DESC
                        LIMIT :limit
                        """)
                .param("c", companyId).param("sku", sku).param("location", location).param("limit", limit)
                .query((rs, n) -> new MovementRow(rs.getObject("event_id", UUID.class), rs.getString("type"),
                        rs.getString("document_number"), rs.getString("sku"), rs.getString("location"),
                        state(rs.getString("from_state")), state(rs.getString("to_state")),
                        rs.getBigDecimal("quantity"), rs.getObject("occurred_at", OffsetDateTime.class)))
                .list();
    }

    record AlertRow(UUID eventId, String kind, String sku, String location, BigDecimal expected, BigDecimal actual,
                    String message, OffsetDateTime recordedAt) {
    }

    List<AlertRow> alerts(UUID companyId, int limit) {
        return jdbc.sql("""
                        SELECT event_id, kind, sku, location, expected, actual, message, recorded_at FROM stock_alert
                        WHERE company_id = :c ORDER BY recorded_at DESC, id LIMIT :limit
                        """)
                .param("c", companyId).param("limit", limit)
                .query((rs, n) -> new AlertRow(rs.getObject("event_id", UUID.class), rs.getString("kind"),
                        rs.getString("sku"), rs.getString("location"), rs.getBigDecimal("expected"),
                        rs.getBigDecimal("actual"), rs.getString("message"),
                        rs.getObject("recorded_at", OffsetDateTime.class)))
                .list();
    }

    private static StockState state(String value) {
        return value == null ? null : StockState.valueOf(value);
    }
}
