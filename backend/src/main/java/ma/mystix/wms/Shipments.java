package ma.mystix.wms;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import ma.mystix.shared.error.ErrorCode;
import ma.mystix.shared.error.MystixException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Picking and shipping (ADR-0014, MVP): a pick list of lots and locations chosen by hand (PICKING), then the
 * confirmation of what was really picked, which takes it out of stock (SHIPPED). Only released lots outside quarantine
 * leave, and never stock reserved for someone else. No transport management.
 */
@Service
public class Shipments {

    private final JdbcClient jdbc;
    private final WmsCatalog catalog;
    private final Lots lots;
    private final Ledger ledger;
    private final AtelysAudit audit;
    private final Clock clock;

    Shipments(JdbcClient jdbc, WmsCatalog catalog, Lots lots, Ledger ledger, AtelysAudit audit, Clock clock) {
        this.jdbc = jdbc;
        this.catalog = catalog;
        this.lots = lots;
        this.ledger = ledger;
        this.audit = audit;
        this.clock = clock;
    }

    public record NewLine(UUID lotId, UUID locationId, BigDecimal quantity, String unit) {
    }

    public record Line(int lineNo, UUID lotId, String lotNumber, UUID productId, String sku, String productName,
                       UUID locationId, String locationCode, BigDecimal quantity, String unit, BigDecimal picked) {
    }

    public record Shipment(UUID id, String number, UUID siteId, String customer, String orderReference,
                           String status, OffsetDateTime createdAt, OffsetDateTime shippedAt, String createdBy,
                           String shippedBy, List<Line> lines) {
    }

    @Transactional
    public Shipment create(UUID companyId, UUID siteId, String customer, String orderReference, List<NewLine> lines,
                           String actor) {
        catalog.site(companyId, siteId);
        if (lines == null || lines.isEmpty()) {
            throw new MystixException(ErrorCode.VALIDATION_FAILED, "A shipment needs at least one line");
        }
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                        INSERT INTO wms_shipment (id, company_id, number, site_id, customer, order_ref, status,
                                                  created_at, created_by)
                        VALUES (:id, :c, :n, :site, :customer, :order, 'PICKING', :now, :actor)
                        """)
                .param("id", id).param("c", companyId).param("n", audit.nextNumber(companyId, "EXP"))
                .param("site", siteId).param("customer", customer).param("order", orderReference)
                .param("now", OffsetDateTime.now(clock)).param("actor", actor).update();
        int no = 0;
        for (NewLine l : lines) {
            Lots.Lot lot = lots.get(companyId, l.lotId());
            WmsCatalog.Product product = catalog.product(companyId, lot.productId());
            catalog.toBase(product, l.quantity(), l.unit());
            WmsCatalog.Location loc = catalog.location(companyId, l.locationId());
            if (!loc.siteId().equals(siteId)) {
                throw new MystixException(ErrorCode.VALIDATION_FAILED, "Location " + loc.code() + " is on another site");
            }
            jdbc.sql("""
                            INSERT INTO wms_shipment_line (shipment_id, line_no, lot_id, location_id, quantity, unit)
                            VALUES (:s, :no, :lot, :loc, :q, :u)
                            """)
                    .param("s", id).param("no", ++no).param("lot", lot.id()).param("loc", loc.id())
                    .param("q", l.quantity()).param("u", l.unit() == null || l.unit().isBlank() ? product.baseUnit()
                            : l.unit().strip().toUpperCase())
                    .update();
        }
        return get(companyId, id);
    }

    /**
     * Confirms what was picked (line number → quantity, defaulting to the planned quantity) and takes it out of
     * stock. Confirming a shipped shipment again returns it unchanged.
     */
    @Transactional
    public Shipment confirm(UUID companyId, UUID shipmentId, Map<Integer, BigDecimal> picked, String actor) {
        jdbc.sql("SELECT id FROM wms_shipment WHERE company_id = :c AND id = :id FOR UPDATE")
                .param("c", companyId).param("id", shipmentId).query(UUID.class).optional()
                .orElseThrow(() -> WmsCatalog.notFound("shipment " + shipmentId));
        Shipment s = get(companyId, shipmentId);
        if ("SHIPPED".equals(s.status())) {
            return s;
        }
        OffsetDateTime now = OffsetDateTime.now(clock);
        for (Line line : s.lines()) {
            BigDecimal q = picked == null ? line.quantity() : picked.getOrDefault(line.lineNo(), line.quantity());
            if (q.signum() < 0 || q.compareTo(line.quantity()) > 0) {
                throw new MystixException(ErrorCode.VALIDATION_FAILED, "Picked quantity of line " + line.lineNo()
                        + " must be between 0 and " + line.quantity());
            }
            jdbc.sql("UPDATE wms_shipment_line SET picked = :q WHERE shipment_id = :s AND line_no = :no")
                    .param("q", q).param("s", shipmentId).param("no", line.lineNo()).update();
            if (q.signum() == 0) {
                continue;
            }
            Lots.Lot lot = lots.get(companyId, line.lotId());
            WmsCatalog.Product product = catalog.product(companyId, lot.productId());
            BigDecimal base = catalog.toBase(product, q, line.unit());
            WmsCatalog.Location loc = catalog.location(companyId, line.locationId());
            ledger.lockProduct(companyId, product.id());
            ledger.checkWithdrawal(companyId, lot, loc, base, BigDecimal.ZERO);
            ledger.post(companyId, new Ledger.Post("SHIPMENT", product.id(), lot.id(), base, q, line.unit(),
                    loc.id(), null, "SHIPMENT", shipmentId, now, actor, "Expédition " + s.number()));
        }
        jdbc.sql("UPDATE wms_shipment SET status = 'SHIPPED', shipped_at = :now, shipped_by = :a WHERE id = :id")
                .param("now", now).param("a", actor).param("id", shipmentId).update();
        return get(companyId, shipmentId);
    }

    public Shipment get(UUID companyId, UUID shipmentId) {
        Shipment head = list(companyId, shipmentId, 1).stream().findFirst()
                .orElseThrow(() -> WmsCatalog.notFound("shipment " + shipmentId));
        List<Line> lines = jdbc.sql("""
                        SELECT sl.*, l.lot_number, l.product_id, p.sku, p.name AS product_name,
                               loc.code AS location_code
                        FROM wms_shipment_line sl
                        JOIN wms_lot l ON l.id = sl.lot_id
                        JOIN wms_product p ON p.id = l.product_id
                        JOIN wms_location loc ON loc.id = sl.location_id
                        WHERE sl.shipment_id = :s ORDER BY sl.line_no
                        """)
                .param("s", shipmentId)
                .query((rs, n) -> new Line(rs.getInt("line_no"), rs.getObject("lot_id", UUID.class),
                        rs.getString("lot_number"), rs.getObject("product_id", UUID.class), rs.getString("sku"),
                        rs.getString("product_name"), rs.getObject("location_id", UUID.class),
                        rs.getString("location_code"), rs.getBigDecimal("quantity"), rs.getString("unit"),
                        rs.getBigDecimal("picked")))
                .list();
        return new Shipment(head.id(), head.number(), head.siteId(), head.customer(), head.orderReference(),
                head.status(), head.createdAt(), head.shippedAt(), head.createdBy(), head.shippedBy(), lines);
    }

    public List<Shipment> list(UUID companyId, UUID shipmentId, int limit) {
        return jdbc.sql("""
                        SELECT * FROM wms_shipment
                        WHERE company_id = :c AND (CAST(:id AS uuid) IS NULL OR id = :id)
                        ORDER BY created_at DESC LIMIT :limit
                        """)
                .param("c", companyId).param("id", shipmentId).param("limit", limit)
                .query((rs, n) -> new Shipment(rs.getObject("id", UUID.class), rs.getString("number"),
                        rs.getObject("site_id", UUID.class), rs.getString("customer"), rs.getString("order_ref"),
                        rs.getString("status"), rs.getObject("created_at", OffsetDateTime.class),
                        rs.getObject("shipped_at", OffsetDateTime.class), rs.getString("created_by"),
                        rs.getString("shipped_by"), List.of()))
                .list();
    }
}
