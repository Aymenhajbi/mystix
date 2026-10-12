package ma.mystix.wms;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import ma.mystix.shared.error.ErrorCode;
import ma.mystix.shared.error.MystixException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * Lots (ADR-0014). Every stock quantity belongs to a lot: a product that is not lot-tracked has one lot,
 * {@value #UNTRACKED}, always released. A lot's quality status decides whether it can be consumed or shipped.
 */
@Service
public class Lots {

    public static final String UNTRACKED = "SANS-LOT";
    public static final List<String> QUALITY = List.of("PENDING", "RELEASED", "BLOCKED", "REJECTED");

    private static final SecureRandom RANDOM = new SecureRandom();

    private final JdbcClient jdbc;
    private final WmsCatalog catalog;
    private final AtelysAudit audit;
    private final JsonMapper json;
    private final Clock clock;

    Lots(JdbcClient jdbc, WmsCatalog catalog, AtelysAudit audit, JsonMapper json, Clock clock) {
        this.jdbc = jdbc;
        this.catalog = catalog;
        this.audit = audit;
        this.json = json;
        this.clock = clock;
    }

    /**
     * @param publicCode opaque identifier for the QR code label (no secret, no personal data)
     * @param attributes typed characteristics validated with the business: moisture, wood species, heat treatment...
     */
    public record Lot(UUID id, UUID productId, String sku, String productName, String unit, String lotNumber,
                      String origin, String qualityStatus, String supplier, String supplierLot, UUID workOrderId,
                      String publicCode, Map<String, Object> attributes, OffsetDateTime receivedAt,
                      OffsetDateTime createdAt) {
    }

    /** A new lot, or the existing one with the same number for this product (a supplier lot delivered twice). */
    @Transactional
    public Lot create(UUID companyId, UUID productId, String lotNumber, String origin, String quality,
                      String supplier, String supplierLot, UUID workOrderId, Map<String, Object> attributes,
                      OffsetDateTime receivedAt) {
        WmsCatalog.Product product = catalog.product(companyId, productId);
        String number = lotNumber == null || lotNumber.isBlank() ? null : lotNumber.strip();
        if (!product.lotTracked()) {
            return untracked(companyId, product);
        }
        if (number != null) {
            var existing = find(companyId, productId, number);
            if (existing != null) {
                return existing;
            }
        } else {
            number = "L" + audit.nextNumber(companyId, "LOT").substring(4).replace("-", "");
        }
        UUID id = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now(clock);
        try {
            jdbc.sql("""
                            INSERT INTO wms_lot (id, company_id, product_id, lot_number, origin, quality_status,
                                                 supplier, supplier_lot, work_order_id, public_code, attributes,
                                                 received_at, created_at, updated_at)
                            VALUES (:id, :c, :p, :n, :origin, :q, :supplier, :slot, :wo, :code, CAST(:attrs AS jsonb),
                                    :received, :now, :now)
                            """)
                    .param("id", id).param("c", companyId).param("p", productId).param("n", number)
                    .param("origin", origin).param("q", quality).param("supplier", supplier)
                    .param("slot", supplierLot).param("wo", workOrderId).param("code", publicCode())
                    .param("attrs", json.writeValueAsString(attributes == null ? Map.of() : attributes))
                    .param("received", receivedAt).param("now", now).update();
        } catch (DuplicateKeyException e) {
            throw new MystixException(ErrorCode.ATELYS_CODE_TAKEN, "Lot " + number + " exists", e);
        }
        return get(companyId, id);
    }

    private Lot untracked(UUID companyId, WmsCatalog.Product product) {
        var existing = find(companyId, product.id(), UNTRACKED);
        if (existing != null) {
            return existing;
        }
        OffsetDateTime now = OffsetDateTime.now(clock);
        jdbc.sql("""
                        INSERT INTO wms_lot (id, company_id, product_id, lot_number, origin, quality_status, public_code,
                                             created_at, updated_at)
                        VALUES (:id, :c, :p, :n, 'UNTRACKED', 'RELEASED', :code, :now, :now)
                        ON CONFLICT (company_id, product_id, lot_number) DO NOTHING
                        """)
                .param("id", UUID.randomUUID()).param("c", companyId).param("p", product.id())
                .param("n", UNTRACKED).param("code", publicCode()).param("now", now).update();
        return find(companyId, product.id(), UNTRACKED);
    }

    /** Release, block or reject a lot: a sensitive action, audited with its reason. */
    @Transactional
    public Lot setQuality(UUID companyId, UUID lotId, String status, String reason, String actor) {
        Lot lot = get(companyId, lotId);
        if (!QUALITY.contains(status) || "PENDING".equals(status)) {
            throw new MystixException(ErrorCode.VALIDATION_FAILED, "Quality status " + status + " cannot be set");
        }
        if (UNTRACKED.equals(lot.lotNumber())) {
            throw new MystixException(ErrorCode.STATUS_TRANSITION_INVALID, "Untracked stock has no quality status");
        }
        if (!"RELEASED".equals(status) && (reason == null || reason.isBlank())) {
            throw new MystixException(ErrorCode.JUSTIFICATION_REQUIRED, "A reason is required to " + status);
        }
        jdbc.sql("UPDATE wms_lot SET quality_status = :s, updated_at = :now WHERE company_id = :c AND id = :id")
                .param("s", status).param("now", OffsetDateTime.now(clock)).param("c", companyId).param("id", lotId)
                .update();
        audit.record(companyId, "LOT_" + status, "LOT", lotId, actor, reason,
                lot.lotNumber() + " " + lot.qualityStatus() + " -> " + status);
        return get(companyId, lotId);
    }

    /** Genealogy: {@code child} was made from (or derived from) {@code parent}. Repeating it changes nothing. */
    public void link(UUID companyId, UUID parentLotId, UUID childLotId, UUID workOrderId,
                     java.math.BigDecimal quantity) {
        jdbc.sql("""
                        INSERT INTO wms_lot_link (id, company_id, parent_lot_id, child_lot_id, work_order_id, quantity,
                                                  created_at)
                        VALUES (:id, :c, :parent, :child, :wo, :q, :now)
                        ON CONFLICT (parent_lot_id, child_lot_id, work_order_id) DO NOTHING
                        """)
                .param("id", UUID.randomUUID()).param("c", companyId).param("parent", parentLotId)
                .param("child", childLotId).param("wo", workOrderId).param("q", quantity)
                .param("now", OffsetDateTime.now(clock)).update();
    }

    public Lot get(UUID companyId, UUID lotId) {
        return query(companyId, "l.id = :key", lotId, 1, null).stream().findFirst()
                .orElseThrow(() -> WmsCatalog.notFound("lot " + lotId));
    }

    /** A scanned or typed code: the QR public code, or a lot number (several products may share one). */
    public List<Lot> resolve(UUID companyId, String code) {
        String c = code == null ? "" : code.strip();
        List<Lot> byCode = query(companyId, "l.public_code = :key", c, 1, null);
        return byCode.isEmpty() ? query(companyId, "lower(l.lot_number) = lower(:key)", c, 20, null) : byCode;
    }

    public List<Lot> search(UUID companyId, String text, UUID productId, int limit) {
        String like = text == null || text.isBlank() ? "%" : "%" + text.strip().toLowerCase() + "%";
        return query(companyId, "(lower(l.lot_number) LIKE :key OR lower(p.sku) LIKE :key OR lower(p.name) LIKE :key)",
                like, limit, productId);
    }

    private Lot find(UUID companyId, UUID productId, String number) {
        return query(companyId, "l.lot_number = :key", number, 1, productId).stream().findFirst().orElse(null);
    }

    private List<Lot> query(UUID companyId, String where, Object key, int limit, UUID productId) {
        return jdbc.sql("""
                        SELECT l.*, p.sku, p.name AS product_name, p.base_unit FROM wms_lot l
                        JOIN wms_product p ON p.id = l.product_id
                        WHERE l.company_id = :c AND %s AND (CAST(:p AS uuid) IS NULL OR l.product_id = :p)
                        ORDER BY l.created_at DESC LIMIT :limit
                        """.formatted(where))
                .param("c", companyId).param("key", key).param("p", productId).param("limit", limit)
                .query((rs, n) -> new Lot(rs.getObject("id", UUID.class), rs.getObject("product_id", UUID.class),
                        rs.getString("sku"), rs.getString("product_name"), rs.getString("base_unit"),
                        rs.getString("lot_number"), rs.getString("origin"), rs.getString("quality_status"),
                        rs.getString("supplier"), rs.getString("supplier_lot"),
                        rs.getObject("work_order_id", UUID.class), rs.getString("public_code"),
                        attributes(rs.getString("attributes")), rs.getObject("received_at", OffsetDateTime.class),
                        rs.getObject("created_at", OffsetDateTime.class)))
                .list();
    }

    private Map<String, Object> attributes(String text) {
        return text == null ? Map.of() : json.readValue(text, new TypeReference<Map<String, Object>>() { });
    }

    private static String publicCode() {
        byte[] bytes = new byte[12];
        RANDOM.nextBytes(bytes);
        return "MX" + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
