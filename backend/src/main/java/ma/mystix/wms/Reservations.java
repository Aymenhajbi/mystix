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
 * Reservations of available stock (ADR-0014), for a work order or a customer order, on a product at a site and
 * optionally on one lot. Made under the product lock: two concurrent reservations cannot both take the same units.
 */
@Service
public class Reservations {

    private final JdbcClient jdbc;
    private final Ledger ledger;
    private final WmsCatalog catalog;
    private final Clock clock;

    Reservations(JdbcClient jdbc, Ledger ledger, WmsCatalog catalog, Clock clock) {
        this.jdbc = jdbc;
        this.ledger = ledger;
        this.catalog = catalog;
        this.clock = clock;
    }

    public record Reservation(UUID id, UUID productId, String sku, UUID lotId, UUID siteId, BigDecimal quantity,
                              BigDecimal consumedQuantity, String purpose, String reference, UUID referenceId,
                              String status, OffsetDateTime createdAt) {

        public BigDecimal remaining() {
            return quantity.subtract(consumedQuantity);
        }
    }

    @Transactional
    public Reservation reserve(UUID companyId, UUID productId, UUID lotId, UUID siteId, BigDecimal quantity,
                               String purpose, String reference, UUID referenceId, String actor) {
        catalog.product(companyId, productId);
        catalog.site(companyId, siteId);
        if (quantity == null || quantity.signum() <= 0) {
            throw new MystixException(ErrorCode.VALIDATION_FAILED, "Reservation quantity must be positive");
        }
        ledger.lockProduct(companyId, productId);
        Ledger.Availability product = ledger.availability(companyId, productId, null, siteId);
        BigDecimal available = product.available();
        if (lotId != null) {
            available = available.min(ledger.availability(companyId, productId, lotId, siteId).available());
        }
        if (available.compareTo(quantity) < 0) {
            throw new MystixException(ErrorCode.STOCK_NOT_AVAILABLE,
                    "Reserve " + quantity + " of " + productId + ": only " + available + " available");
        }
        UUID id = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now(clock);
        jdbc.sql("""
                        INSERT INTO wms_reservation (id, company_id, product_id, lot_id, site_id, quantity, purpose,
                                                     reference, reference_id, status, created_at, updated_at, actor)
                        VALUES (:id, :c, :p, :lot, :site, :q, :purpose, :ref, :refId, 'ACTIVE', :now, :now, :actor)
                        """)
                .param("id", id).param("c", companyId).param("p", productId).param("lot", lotId)
                .param("site", siteId).param("q", quantity).param("purpose", purpose).param("ref", reference)
                .param("refId", referenceId).param("now", now).param("actor", actor).update();
        return get(companyId, id);
    }

    @Transactional
    public Reservation release(UUID companyId, UUID reservationId) {
        Reservation r = get(companyId, reservationId);
        if (!"ACTIVE".equals(r.status())) {
            throw new MystixException(ErrorCode.STATUS_TRANSITION_INVALID, "Reservation is " + r.status());
        }
        jdbc.sql("UPDATE wms_reservation SET status = 'RELEASED', updated_at = :now WHERE id = :id")
                .param("now", OffsetDateTime.now(clock)).param("id", reservationId).update();
        return get(companyId, reservationId);
    }

    /** Releases every active reservation of a business document (a cancelled or closed work order). */
    @Transactional
    public int releaseAll(UUID companyId, UUID referenceId) {
        return jdbc.sql("""
                        UPDATE wms_reservation SET status = 'RELEASED', updated_at = :now
                        WHERE company_id = :c AND reference_id = :ref AND status = 'ACTIVE'
                        """)
                .param("now", OffsetDateTime.now(clock)).param("c", companyId).param("ref", referenceId).update();
    }

    /**
     * Uses up to {@code quantity} of the active reservations of a document for this product (and lot, when they name
     * one); returns how much was covered. Fully used reservations become CONSUMED.
     */
    @Transactional
    public BigDecimal consume(UUID companyId, UUID referenceId, UUID productId, UUID lotId, BigDecimal quantity) {
        BigDecimal left = quantity;
        for (Reservation r : forReference(companyId, referenceId)) {
            if (left.signum() <= 0) {
                break;
            }
            if (!"ACTIVE".equals(r.status()) || !r.productId().equals(productId)
                    || (r.lotId() != null && !r.lotId().equals(lotId))) {
                continue;
            }
            BigDecimal used = left.min(r.remaining());
            jdbc.sql("""
                            UPDATE wms_reservation
                            SET consumed_quantity = consumed_quantity + :used,
                                status = CASE WHEN consumed_quantity + :used >= quantity THEN 'CONSUMED' ELSE status END,
                                updated_at = :now
                            WHERE id = :id
                            """)
                    .param("used", used).param("now", OffsetDateTime.now(clock)).param("id", r.id()).update();
            left = left.subtract(used);
        }
        return quantity.subtract(left);
    }

    /** What the document's active reservations still hold for this product (and lot). */
    public BigDecimal remaining(UUID companyId, UUID referenceId, UUID productId, UUID lotId) {
        return forReference(companyId, referenceId).stream()
                .filter(r -> "ACTIVE".equals(r.status()) && r.productId().equals(productId)
                        && (r.lotId() == null || r.lotId().equals(lotId)))
                .map(Reservation::remaining).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    public List<Reservation> forReference(UUID companyId, UUID referenceId) {
        return query(companyId, "r.reference_id = :key", referenceId);
    }

    public List<Reservation> active(UUID companyId, UUID productId) {
        return query(companyId, "r.product_id = :key AND r.status = 'ACTIVE'", productId);
    }

    public Reservation get(UUID companyId, UUID id) {
        return query(companyId, "r.id = :key", id).stream().findFirst()
                .orElseThrow(() -> WmsCatalog.notFound("reservation " + id));
    }

    private List<Reservation> query(UUID companyId, String where, Object key) {
        return jdbc.sql("""
                        SELECT r.*, p.sku FROM wms_reservation r JOIN wms_product p ON p.id = r.product_id
                        WHERE r.company_id = :c AND %s ORDER BY r.created_at
                        """.formatted(where))
                .param("c", companyId).param("key", key)
                .query((rs, n) -> new Reservation(rs.getObject("id", UUID.class),
                        rs.getObject("product_id", UUID.class), rs.getString("sku"),
                        rs.getObject("lot_id", UUID.class), rs.getObject("site_id", UUID.class),
                        rs.getBigDecimal("quantity"), rs.getBigDecimal("consumed_quantity"), rs.getString("purpose"),
                        rs.getString("reference"), rs.getObject("reference_id", UUID.class), rs.getString("status"),
                        rs.getObject("created_at", OffsetDateTime.class)))
                .list();
    }
}
