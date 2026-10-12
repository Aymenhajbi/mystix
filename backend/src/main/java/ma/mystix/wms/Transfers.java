package ma.mystix.wms;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import ma.mystix.shared.error.ErrorCode;
import ma.mystix.shared.error.MystixException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Transfers of one lot (ADR-0014). Within a site, shipping moves the stock at once (DRAFT → RECEIVED). Between
 * sites, shipping puts it in the destination site's TRANSIT location (DRAFT → SHIPPED) and receiving moves it to the
 * target (SHIPPED → RECEIVED). No quantity is created or destroyed.
 */
@Service
public class Transfers {

    private final JdbcClient jdbc;
    private final WmsCatalog catalog;
    private final Lots lots;
    private final Ledger ledger;
    private final AtelysAudit audit;
    private final Clock clock;

    Transfers(JdbcClient jdbc, WmsCatalog catalog, Lots lots, Ledger ledger, AtelysAudit audit, Clock clock) {
        this.jdbc = jdbc;
        this.catalog = catalog;
        this.lots = lots;
        this.ledger = ledger;
        this.audit = audit;
        this.clock = clock;
    }

    public record Transfer(UUID id, String number, UUID lotId, String lotNumber, UUID productId, String sku,
                           BigDecimal quantity, String unit, UUID fromLocationId, String fromLocation,
                           UUID fromSiteId, UUID toLocationId, String toLocation, UUID toSiteId, String status,
                           OffsetDateTime createdAt, OffsetDateTime shippedAt, OffsetDateTime receivedAt,
                           String createdBy) {

        public boolean intersite() {
            return !fromSiteId.equals(toSiteId);
        }
    }

    @Transactional
    public Transfer create(UUID companyId, UUID lotId, BigDecimal quantity, String unit, UUID fromLocationId,
                           UUID toLocationId, String actor) {
        Lots.Lot lot = lots.get(companyId, lotId);
        WmsCatalog.Product product = catalog.product(companyId, lot.productId());
        catalog.toBase(product, quantity, unit);
        WmsCatalog.Location from = catalog.location(companyId, fromLocationId);
        WmsCatalog.Location to = catalog.location(companyId, toLocationId);
        if (from.id().equals(to.id()) || "TRANSIT".equals(from.type()) || "TRANSIT".equals(to.type())) {
            throw new MystixException(ErrorCode.VALIDATION_FAILED, "Choose two different storage locations");
        }
        UUID id = UUID.randomUUID();
        String number = audit.nextNumber(companyId, "TRF");
        try {
            jdbc.sql("""
                            INSERT INTO wms_transfer (id, company_id, number, lot_id, quantity, unit, from_location_id,
                                                      to_location_id, status, created_at, created_by)
                            VALUES (:id, :c, :n, :lot, :q, :u, :from, :to, 'DRAFT', :now, :actor)
                            """)
                    .param("id", id).param("c", companyId).param("n", number).param("lot", lotId)
                    .param("q", quantity).param("u", unit == null || unit.isBlank() ? product.baseUnit()
                            : unit.strip().toUpperCase())
                    .param("from", from.id()).param("to", to.id()).param("now", OffsetDateTime.now(clock))
                    .param("actor", actor).update();
        } catch (DuplicateKeyException e) {
            throw new MystixException(ErrorCode.ATELYS_CODE_TAKEN, "Transfer " + number + " exists", e);
        }
        return get(companyId, id);
    }

    /** Ships a draft transfer. Shipping it again returns it unchanged. */
    @Transactional
    public Transfer ship(UUID companyId, UUID transferId, String actor) {
        Transfer t = locked(companyId, transferId);
        if (!"DRAFT".equals(t.status())) {
            if ("SHIPPED".equals(t.status()) || "RECEIVED".equals(t.status())) {
                return t;
            }
            throw new MystixException(ErrorCode.STATUS_TRANSITION_INVALID, "Transfer is " + t.status());
        }
        WmsCatalog.Product product = catalog.product(companyId, t.productId());
        BigDecimal base = catalog.toBase(product, t.quantity(), t.unit());
        Lots.Lot lot = lots.get(companyId, t.lotId());
        OffsetDateTime now = OffsetDateTime.now(clock);
        if (t.intersite()) {
            // Released stock leaving a site must not take what is reserved there.
            if ("RELEASED".equals(lot.qualityStatus())) {
                ledger.lockProduct(companyId, product.id());
                BigDecimal available = ledger.availability(companyId, product.id(), null, t.fromSiteId()).available();
                if (available.compareTo(base) < 0) {
                    throw new MystixException(ErrorCode.STOCK_NOT_AVAILABLE, "Only " + available + " free at site");
                }
            }
            UUID transit = catalog.site(companyId, t.toSiteId()).transitLocationId();
            ledger.post(companyId, new Ledger.Post("TRANSFER_SHIP", product.id(), lot.id(), base, t.quantity(),
                    t.unit(), t.fromLocationId(), transit, "TRANSFER", t.id(), now, actor, "Transfert " + t.number()));
            setStatus(t.id(), "SHIPPED", "shipped_at", now);
        } else {
            ledger.post(companyId, new Ledger.Post("MOVE", product.id(), lot.id(), base, t.quantity(), t.unit(),
                    t.fromLocationId(), t.toLocationId(), "TRANSFER", t.id(), now, actor, "Transfert " + t.number()));
            setStatus(t.id(), "RECEIVED", "received_at", now);
        }
        return get(companyId, transferId);
    }

    /** Receives a shipped intersite transfer at its target. Receiving it again returns it unchanged. */
    @Transactional
    public Transfer receive(UUID companyId, UUID transferId, String actor) {
        Transfer t = locked(companyId, transferId);
        if ("RECEIVED".equals(t.status())) {
            return t;
        }
        if (!"SHIPPED".equals(t.status())) {
            throw new MystixException(ErrorCode.STATUS_TRANSITION_INVALID, "Transfer is " + t.status());
        }
        WmsCatalog.Product product = catalog.product(companyId, t.productId());
        BigDecimal base = catalog.toBase(product, t.quantity(), t.unit());
        OffsetDateTime now = OffsetDateTime.now(clock);
        UUID transit = catalog.site(companyId, t.toSiteId()).transitLocationId();
        ledger.post(companyId, new Ledger.Post("TRANSFER_RECEIVE", product.id(), t.lotId(), base, t.quantity(),
                t.unit(), transit, t.toLocationId(), "TRANSFER", t.id(), now, actor, "Transfert " + t.number()));
        setStatus(t.id(), "RECEIVED", "received_at", now);
        return get(companyId, transferId);
    }

    private void setStatus(UUID id, String status, String column, OffsetDateTime at) {
        jdbc.sql("UPDATE wms_transfer SET status = :s, " + column + " = :at WHERE id = :id")
                .param("s", status).param("at", at).param("id", id).update();
    }

    private Transfer locked(UUID companyId, UUID transferId) {
        jdbc.sql("SELECT id FROM wms_transfer WHERE company_id = :c AND id = :id FOR UPDATE")
                .param("c", companyId).param("id", transferId).query(UUID.class).optional()
                .orElseThrow(() -> WmsCatalog.notFound("transfer " + transferId));
        return get(companyId, transferId);
    }

    public Transfer get(UUID companyId, UUID transferId) {
        return list(companyId, transferId, 1).stream().findFirst()
                .orElseThrow(() -> WmsCatalog.notFound("transfer " + transferId));
    }

    public List<Transfer> list(UUID companyId, UUID transferId, int limit) {
        return jdbc.sql("""
                        SELECT t.*, l.lot_number, l.product_id, p.sku, f.code AS from_code, f.site_id AS from_site,
                               d.code AS to_code, d.site_id AS to_site
                        FROM wms_transfer t
                        JOIN wms_lot l ON l.id = t.lot_id
                        JOIN wms_product p ON p.id = l.product_id
                        JOIN wms_location f ON f.id = t.from_location_id
                        JOIN wms_location d ON d.id = t.to_location_id
                        WHERE t.company_id = :c AND (CAST(:id AS uuid) IS NULL OR t.id = :id)
                        ORDER BY t.created_at DESC LIMIT :limit
                        """)
                .param("c", companyId).param("id", transferId).param("limit", limit)
                .query((rs, n) -> new Transfer(rs.getObject("id", UUID.class), rs.getString("number"),
                        rs.getObject("lot_id", UUID.class), rs.getString("lot_number"),
                        rs.getObject("product_id", UUID.class), rs.getString("sku"), rs.getBigDecimal("quantity"),
                        rs.getString("unit"), rs.getObject("from_location_id", UUID.class), rs.getString("from_code"),
                        rs.getObject("from_site", UUID.class), rs.getObject("to_location_id", UUID.class),
                        rs.getString("to_code"), rs.getObject("to_site", UUID.class), rs.getString("status"),
                        rs.getObject("created_at", OffsetDateTime.class),
                        rs.getObject("shipped_at", OffsetDateTime.class),
                        rs.getObject("received_at", OffsetDateTime.class), rs.getString("created_by")))
                .list();
    }
}
