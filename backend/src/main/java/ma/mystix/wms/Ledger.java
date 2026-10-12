package ma.mystix.wms;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import ma.mystix.shared.error.ErrorCode;
import ma.mystix.shared.error.MystixException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The Atelys stock ledger (ADR-0014): append-only movements and their balance per lot and location, updated in the
 * same transaction. A balance never goes negative (database constraint and conditional update), so two concurrent
 * withdrawals cannot both take the last unit. Reservations and availability checks of one product are serialised by a
 * transaction-scoped advisory lock.
 * <p>
 * Definitions, in the product's base unit, outside TRANSIT locations unless stated:
 * <ul>
 *   <li>physical: what the movements say is there;</li>
 *   <li>blocked: physical stock of lots not released, or in a QUARANTINE location;</li>
 *   <li>reserved: active reservations, minus what they already consumed;</li>
 *   <li>available = physical − blocked − reserved (a reservation is made on available stock only, so nothing is
 *   subtracted twice);</li>
 *   <li>in transit: stock in the TRANSIT location of a site, shipped from another site and not yet received.</li>
 * </ul>
 */
@Service
public class Ledger {

    private final JdbcClient jdbc;
    private final Clock clock;

    Ledger(JdbcClient jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    /** A movement to record; {@code quantity} in the base unit, {@code enteredQuantity} as typed. */
    public record Post(String type, UUID productId, UUID lotId, BigDecimal quantity, BigDecimal enteredQuantity,
                       String enteredUnit, UUID fromLocationId, UUID toLocationId, String referenceType,
                       UUID referenceId, OffsetDateTime occurredAt, String actor, String reason) {
    }

    public record Movement(UUID id, String type, UUID productId, String sku, UUID lotId, String lotNumber,
                           BigDecimal quantity, String unit, BigDecimal enteredQuantity, String enteredUnit,
                           UUID fromLocationId, String fromLocation, UUID toLocationId, String toLocation,
                           String referenceType, UUID referenceId, OffsetDateTime occurredAt, String actor,
                           String reason) {
    }

    public record Availability(BigDecimal physical, BigDecimal blocked, BigDecimal reserved, BigDecimal available,
                               BigDecimal inTransit) {
    }

    /** Records a movement and moves the balances; refused whole when the source does not hold the quantity. */
    @Transactional(propagation = Propagation.MANDATORY)
    public UUID post(UUID companyId, Post p) {
        OffsetDateTime now = OffsetDateTime.now(clock);
        if (p.fromLocationId() != null) {
            int taken = jdbc.sql("""
                            UPDATE wms_balance SET quantity = quantity - :q, updated_at = :now
                            WHERE company_id = :c AND lot_id = :lot AND location_id = :loc AND quantity >= :q
                            """)
                    .param("q", p.quantity()).param("now", now).param("c", companyId).param("lot", p.lotId())
                    .param("loc", p.fromLocationId()).update();
            if (taken == 0) {
                throw new MystixException(ErrorCode.STOCK_NOT_AVAILABLE,
                        "Lot " + p.lotId() + " at " + p.fromLocationId() + " holds less than " + p.quantity());
            }
        }
        if (p.toLocationId() != null) {
            jdbc.sql("""
                            INSERT INTO wms_balance (company_id, lot_id, location_id, product_id, quantity, updated_at)
                            VALUES (:c, :lot, :loc, :p, :q, :now)
                            ON CONFLICT (lot_id, location_id)
                            DO UPDATE SET quantity = wms_balance.quantity + EXCLUDED.quantity, updated_at = :now
                            """)
                    .param("c", companyId).param("lot", p.lotId()).param("loc", p.toLocationId())
                    .param("p", p.productId()).param("q", p.quantity()).param("now", now).update();
        }
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                        INSERT INTO wms_movement (id, company_id, type, product_id, lot_id, quantity, entered_quantity,
                                                  entered_unit, from_location_id, to_location_id, reference_type,
                                                  reference_id, occurred_at, recorded_at, actor, reason)
                        VALUES (:id, :c, :type, :p, :lot, :q, :eq, :eu, :from, :to, :rt, :rid, :at, :now, :actor,
                                :reason)
                        """)
                .param("id", id).param("c", companyId).param("type", p.type()).param("p", p.productId())
                .param("lot", p.lotId()).param("q", p.quantity()).param("eq", p.enteredQuantity())
                .param("eu", p.enteredUnit()).param("from", p.fromLocationId()).param("to", p.toLocationId())
                .param("rt", p.referenceType()).param("rid", p.referenceId())
                .param("at", p.occurredAt() == null ? now : p.occurredAt()).param("now", now)
                .param("actor", p.actor()).param("reason", p.reason()).update();
        return id;
    }

    /** Serialises availability checks and reservations of one product until the transaction ends. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void lockProduct(UUID companyId, UUID productId) {
        long key = productId.getMostSignificantBits() ^ productId.getLeastSignificantBits()
                ^ companyId.getLeastSignificantBits();
        jdbc.sql("SELECT pg_advisory_xact_lock(:k)").param("k", key).query().singleRow();
    }

    /**
     * Stock of a product, optionally restricted to one lot and one site. With a lot, reservations made on the product
     * without a lot are not counted: check the product as well before withdrawing.
     */
    public Availability availability(UUID companyId, UUID productId, UUID lotId, UUID siteId) {
        var stock = jdbc.sql("""
                        SELECT
                          COALESCE(SUM(b.quantity) FILTER (WHERE loc.type <> 'TRANSIT'), 0) AS physical,
                          COALESCE(SUM(b.quantity) FILTER (WHERE loc.type <> 'TRANSIT'
                              AND (l.quality_status <> 'RELEASED' OR loc.type = 'QUARANTINE')), 0) AS blocked,
                          COALESCE(SUM(b.quantity) FILTER (WHERE loc.type = 'TRANSIT'), 0) AS transit
                        FROM wms_balance b
                        JOIN wms_lot l ON l.id = b.lot_id
                        JOIN wms_location loc ON loc.id = b.location_id
                        WHERE b.company_id = :c AND b.product_id = :p
                          AND (CAST(:lot AS uuid) IS NULL OR b.lot_id = :lot)
                          AND (CAST(:site AS uuid) IS NULL OR loc.site_id = :site)
                        """)
                .param("c", companyId).param("p", productId).param("lot", lotId).param("site", siteId)
                .query((rs, n) -> new BigDecimal[] {rs.getBigDecimal("physical"), rs.getBigDecimal("blocked"),
                        rs.getBigDecimal("transit")})
                .single();
        BigDecimal reserved = jdbc.sql("""
                        SELECT COALESCE(SUM(quantity - consumed_quantity), 0) FROM wms_reservation
                        WHERE company_id = :c AND product_id = :p AND status = 'ACTIVE'
                          AND (CAST(:lot AS uuid) IS NULL OR lot_id = :lot)
                          AND (CAST(:site AS uuid) IS NULL OR site_id = :site)
                        """)
                .param("c", companyId).param("p", productId).param("lot", lotId).param("site", siteId)
                .query(BigDecimal.class).single();
        return new Availability(stock[0], stock[1], reserved, stock[0].subtract(stock[1]).subtract(reserved),
                stock[2]);
    }

    /**
     * Checks, under the product lock, that {@code quantity} of a released lot can leave {@code locationId} without
     * taking reserved stock (beyond {@code ownReservation}, the part of the withdrawal covered by the caller's own
     * reservation). The balance itself is checked again by {@link #post}.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void checkWithdrawal(UUID companyId, Lots.Lot lot, WmsCatalog.Location location, BigDecimal quantity,
                                BigDecimal ownReservation) {
        if (!"RELEASED".equals(lot.qualityStatus())) {
            throw new MystixException(ErrorCode.LOT_NOT_RELEASED, "Lot " + lot.lotNumber() + " is "
                    + lot.qualityStatus());
        }
        if ("QUARANTINE".equals(location.type()) || "TRANSIT".equals(location.type())) {
            throw new MystixException(ErrorCode.LOT_NOT_RELEASED, "Location " + location.code() + " is "
                    + location.type());
        }
        BigDecimal free = quantity.subtract(ownReservation == null ? BigDecimal.ZERO : ownReservation).max(BigDecimal.ZERO);
        if (free.signum() == 0) {
            return;
        }
        Availability product = availability(companyId, lot.productId(), null, location.siteId());
        Availability ofLot = availability(companyId, lot.productId(), lot.id(), location.siteId());
        if (product.available().compareTo(free) < 0 || ofLot.available().compareTo(free) < 0) {
            throw new MystixException(ErrorCode.STOCK_NOT_AVAILABLE, "Only " + product.available().min(
                    ofLot.available()) + " available for " + lot.sku() + " at site " + location.siteId());
        }
    }

    public BigDecimal balance(UUID companyId, UUID lotId, UUID locationId) {
        return jdbc.sql("SELECT quantity FROM wms_balance WHERE company_id = :c AND lot_id = :lot AND location_id = :loc")
                .param("c", companyId).param("lot", lotId).param("loc", locationId).query(BigDecimal.class)
                .optional().orElse(BigDecimal.ZERO);
    }

    /** Movement history, newest first, filtered by product, lot, location or business document. */
    public List<Movement> movements(UUID companyId, UUID productId, UUID lotId, UUID locationId, UUID referenceId,
                                    int limit) {
        return jdbc.sql("""
                        SELECT m.*, p.sku, p.base_unit, l.lot_number, f.code AS from_code, t.code AS to_code
                        FROM wms_movement m
                        JOIN wms_product p ON p.id = m.product_id
                        JOIN wms_lot l ON l.id = m.lot_id
                        LEFT JOIN wms_location f ON f.id = m.from_location_id
                        LEFT JOIN wms_location t ON t.id = m.to_location_id
                        WHERE m.company_id = :c
                          AND (CAST(:p AS uuid) IS NULL OR m.product_id = :p)
                          AND (CAST(:lot AS uuid) IS NULL OR m.lot_id = :lot)
                          AND (CAST(:loc AS uuid) IS NULL OR m.from_location_id = :loc OR m.to_location_id = :loc)
                          AND (CAST(:ref AS uuid) IS NULL OR m.reference_id = :ref)
                        ORDER BY m.occurred_at DESC, m.recorded_at DESC
                        LIMIT :limit
                        """)
                .param("c", companyId).param("p", productId).param("lot", lotId).param("loc", locationId)
                .param("ref", referenceId).param("limit", limit)
                .query((rs, n) -> new Movement(rs.getObject("id", UUID.class), rs.getString("type"),
                        rs.getObject("product_id", UUID.class), rs.getString("sku"),
                        rs.getObject("lot_id", UUID.class), rs.getString("lot_number"), rs.getBigDecimal("quantity"),
                        rs.getString("base_unit"), rs.getBigDecimal("entered_quantity"), rs.getString("entered_unit"),
                        rs.getObject("from_location_id", UUID.class), rs.getString("from_code"),
                        rs.getObject("to_location_id", UUID.class), rs.getString("to_code"),
                        rs.getString("reference_type"), rs.getObject("reference_id", UUID.class),
                        rs.getObject("occurred_at", OffsetDateTime.class), rs.getString("actor"),
                        rs.getString("reason")))
                .list();
    }
}
