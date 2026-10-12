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
import org.springframework.transaction.annotation.Transactional;

/**
 * Inventory counts of a warehouse (ADR-0014): OPEN (lines with the theoretical quantity frozen at creation, counts
 * entered without touching the stock) → SUBMITTED (every line counted, a reason for every gap) → APPROVED by a
 * manager, which records one ADJUSTMENT movement per gap. The gap is applied to the current balance, so movements
 * made between the count and its approval are kept.
 */
@Service
public class Inventories {

    private final JdbcClient jdbc;
    private final WmsCatalog catalog;
    private final Lots lots;
    private final Ledger ledger;
    private final AtelysAudit audit;
    private final Clock clock;

    Inventories(JdbcClient jdbc, WmsCatalog catalog, Lots lots, Ledger ledger, AtelysAudit audit, Clock clock) {
        this.jdbc = jdbc;
        this.catalog = catalog;
        this.lots = lots;
        this.ledger = ledger;
        this.audit = audit;
        this.clock = clock;
    }

    public record Line(UUID lotId, String lotNumber, UUID productId, String sku, String productName, String unit,
                       UUID locationId, String locationCode, BigDecimal expected, BigDecimal counted,
                       BigDecimal delta, String reason) {
    }

    public record Count(UUID id, String number, UUID warehouseId, String status, OffsetDateTime createdAt,
                        OffsetDateTime submittedAt, OffsetDateTime approvedAt, String createdBy, String submittedBy,
                        String approvedBy, List<Line> lines) {
    }

    public record Entry(UUID lotId, UUID locationId, BigDecimal counted, String reason) {
    }

    @Transactional
    public Count open(UUID companyId, UUID warehouseId, String actor) {
        catalog.warehouse(companyId, warehouseId);
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                        INSERT INTO wms_inventory_count (id, company_id, number, warehouse_id, status, created_at,
                                                         created_by)
                        VALUES (:id, :c, :n, :w, 'OPEN', :now, :actor)
                        """)
                .param("id", id).param("c", companyId).param("n", audit.nextNumber(companyId, "INV"))
                .param("w", warehouseId).param("now", OffsetDateTime.now(clock)).param("actor", actor).update();
        jdbc.sql("""
                        INSERT INTO wms_inventory_line (count_id, lot_id, location_id, expected)
                        SELECT :id, b.lot_id, b.location_id, b.quantity FROM wms_balance b
                        JOIN wms_location loc ON loc.id = b.location_id
                        WHERE b.company_id = :c AND loc.warehouse_id = :w AND b.quantity > 0
                        """)
                .param("id", id).param("c", companyId).param("w", warehouseId).update();
        return get(companyId, id);
    }

    /** Records counted quantities. A lot found where the stock knows none is added with an expected quantity of 0. */
    @Transactional
    public Count count(UUID companyId, UUID countId, List<Entry> entries) {
        Count count = locked(companyId, countId);
        if (!"OPEN".equals(count.status())) {
            throw new MystixException(ErrorCode.STATUS_TRANSITION_INVALID, "Inventory is " + count.status());
        }
        for (Entry e : entries) {
            if (e.counted() == null || e.counted().signum() < 0) {
                throw new MystixException(ErrorCode.VALIDATION_FAILED, "Counted quantity must be zero or more");
            }
            lots.get(companyId, e.lotId());
            WmsCatalog.Location loc = catalog.location(companyId, e.locationId());
            if (!count.warehouseId().equals(loc.warehouseId())) {
                throw new MystixException(ErrorCode.VALIDATION_FAILED, "Location outside the counted warehouse");
            }
            jdbc.sql("""
                            INSERT INTO wms_inventory_line (count_id, lot_id, location_id, expected, counted, reason)
                            VALUES (:id, :lot, :loc, 0, :counted, :reason)
                            ON CONFLICT (count_id, lot_id, location_id)
                            DO UPDATE SET counted = EXCLUDED.counted, reason = EXCLUDED.reason
                            """)
                    .param("id", countId).param("lot", e.lotId()).param("loc", e.locationId())
                    .param("counted", e.counted()).param("reason", e.reason()).update();
        }
        return get(companyId, countId);
    }

    @Transactional
    public Count submit(UUID companyId, UUID countId, String actor) {
        Count count = locked(companyId, countId);
        if (!"OPEN".equals(count.status())) {
            throw new MystixException(ErrorCode.STATUS_TRANSITION_INVALID, "Inventory is " + count.status());
        }
        for (Line line : count.lines()) {
            if (line.counted() == null) {
                throw new MystixException(ErrorCode.VALIDATION_FAILED, "Line " + line.lotNumber() + " at "
                        + line.locationCode() + " is not counted");
            }
            if (line.delta().signum() != 0 && (line.reason() == null || line.reason().isBlank())) {
                throw new MystixException(ErrorCode.JUSTIFICATION_REQUIRED, "Gap without reason on "
                        + line.lotNumber() + " at " + line.locationCode());
            }
        }
        jdbc.sql("UPDATE wms_inventory_count SET status = 'SUBMITTED', submitted_at = :now, submitted_by = :a WHERE id = :id")
                .param("now", OffsetDateTime.now(clock)).param("a", actor).param("id", countId).update();
        return get(companyId, countId);
    }

    /** Applies the gaps as adjustments; for a manager only (checked by the caller). Approving twice changes nothing. */
    @Transactional
    public Count approve(UUID companyId, UUID countId, String actor) {
        Count count = locked(companyId, countId);
        if ("APPROVED".equals(count.status())) {
            return count;
        }
        if (!"SUBMITTED".equals(count.status())) {
            throw new MystixException(ErrorCode.STATUS_TRANSITION_INVALID, "Inventory is " + count.status());
        }
        OffsetDateTime now = OffsetDateTime.now(clock);
        int adjustments = 0;
        for (Line line : count.lines()) {
            if (line.delta().signum() == 0) {
                continue;
            }
            boolean in = line.delta().signum() > 0;
            BigDecimal q = line.delta().abs();
            ledger.post(companyId, new Ledger.Post(in ? "ADJUSTMENT_IN" : "ADJUSTMENT_OUT", line.productId(),
                    line.lotId(), q, q, line.unit(), in ? null : line.locationId(), in ? line.locationId() : null,
                    "INVENTORY", countId, now, actor, "Inventaire " + count.number() + " : " + line.reason()));
            adjustments++;
        }
        jdbc.sql("UPDATE wms_inventory_count SET status = 'APPROVED', approved_at = :now, approved_by = :a WHERE id = :id")
                .param("now", now).param("a", actor).param("id", countId).update();
        audit.record(companyId, "INVENTORY_APPROVED", "INVENTORY", countId, actor, null,
                count.number() + ": " + adjustments + " adjustment(s)");
        return get(companyId, countId);
    }

    private Count locked(UUID companyId, UUID countId) {
        jdbc.sql("SELECT id FROM wms_inventory_count WHERE company_id = :c AND id = :id FOR UPDATE")
                .param("c", companyId).param("id", countId).query(UUID.class).optional()
                .orElseThrow(() -> WmsCatalog.notFound("inventory " + countId));
        return get(companyId, countId);
    }

    public Count get(UUID companyId, UUID countId) {
        Count head = list(companyId, countId, 1).stream().findFirst()
                .orElseThrow(() -> WmsCatalog.notFound("inventory " + countId));
        List<Line> lines = jdbc.sql("""
                        SELECT il.*, l.lot_number, l.product_id, p.sku, p.name AS product_name, p.base_unit,
                               loc.code AS location_code
                        FROM wms_inventory_line il
                        JOIN wms_lot l ON l.id = il.lot_id
                        JOIN wms_product p ON p.id = l.product_id
                        JOIN wms_location loc ON loc.id = il.location_id
                        WHERE il.count_id = :id ORDER BY loc.code, p.sku, l.lot_number
                        """)
                .param("id", countId)
                .query((rs, n) -> {
                    BigDecimal expected = rs.getBigDecimal("expected");
                    BigDecimal counted = rs.getBigDecimal("counted");
                    return new Line(rs.getObject("lot_id", UUID.class), rs.getString("lot_number"),
                            rs.getObject("product_id", UUID.class), rs.getString("sku"),
                            rs.getString("product_name"), rs.getString("base_unit"),
                            rs.getObject("location_id", UUID.class), rs.getString("location_code"), expected, counted,
                            counted == null ? BigDecimal.ZERO : counted.subtract(expected), rs.getString("reason"));
                })
                .list();
        return new Count(head.id(), head.number(), head.warehouseId(), head.status(), head.createdAt(),
                head.submittedAt(), head.approvedAt(), head.createdBy(), head.submittedBy(), head.approvedBy(), lines);
    }

    public List<Count> list(UUID companyId, UUID countId, int limit) {
        return jdbc.sql("""
                        SELECT * FROM wms_inventory_count
                        WHERE company_id = :c AND (CAST(:id AS uuid) IS NULL OR id = :id)
                        ORDER BY created_at DESC LIMIT :limit
                        """)
                .param("c", companyId).param("id", countId).param("limit", limit)
                .query((rs, n) -> new Count(rs.getObject("id", UUID.class), rs.getString("number"),
                        rs.getObject("warehouse_id", UUID.class), rs.getString("status"),
                        rs.getObject("created_at", OffsetDateTime.class),
                        rs.getObject("submitted_at", OffsetDateTime.class),
                        rs.getObject("approved_at", OffsetDateTime.class), rs.getString("created_by"),
                        rs.getString("submitted_by"), rs.getString("approved_by"), List.of()))
                .list();
    }
}
