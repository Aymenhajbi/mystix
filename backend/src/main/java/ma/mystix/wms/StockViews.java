package ma.mystix.wms;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/** Read side of the Atelys stock (ADR-0014): lines per lot and location, totals per product, traceability. */
@Service
public class StockViews {

    private final JdbcClient jdbc;

    StockViews(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public record Filter(UUID siteId, UUID warehouseId, UUID locationId, UUID productId, UUID lotId, String quality,
                         String search) {
    }

    /** One lot at one location. */
    public record StockLine(UUID productId, String sku, String productName, String category, String unit, UUID lotId,
                            String lotNumber, String quality, String publicCode, UUID locationId, String locationCode,
                            String locationType, UUID siteId, String siteCode, BigDecimal quantity,
                            OffsetDateTime updatedAt) {
    }

    /** Totals of a product (optionally at one site), with the five quantities kept apart. */
    public record ProductStock(UUID productId, String sku, String productName, String category, String unit,
                               BigDecimal physical, BigDecimal blocked, BigDecimal reserved, BigDecimal available,
                               BigDecimal inTransit, BigDecimal alertThreshold, boolean belowThreshold) {
    }

    public record GenealogyNode(UUID lotId, String lotNumber, UUID productId, String sku, String productName,
                                String quality, String origin, UUID workOrderId, int depth) {
    }

    public record Genealogy(List<GenealogyNode> upstream, List<GenealogyNode> downstream) {
    }

    public List<StockLine> lines(UUID companyId, Filter f, int limit, int offset) {
        String like = f.search() == null || f.search().isBlank() ? null : "%" + f.search().strip().toLowerCase() + "%";
        return jdbc.sql("""
                        SELECT b.quantity, b.updated_at, p.id AS product_id, p.sku, p.name, p.category, p.base_unit,
                               l.id AS lot_id, l.lot_number, l.quality_status, l.public_code,
                               loc.id AS location_id, loc.code AS location_code, loc.type AS location_type,
                               s.id AS site_id, s.code AS site_code
                        FROM wms_balance b
                        JOIN wms_product p ON p.id = b.product_id
                        JOIN wms_lot l ON l.id = b.lot_id
                        JOIN wms_location loc ON loc.id = b.location_id
                        JOIN wms_site s ON s.id = loc.site_id
                        WHERE b.company_id = :c AND b.quantity > 0
                          AND (CAST(:site AS uuid) IS NULL OR loc.site_id = :site)
                          AND (CAST(:w AS uuid) IS NULL OR loc.warehouse_id = :w)
                          AND (CAST(:loc AS uuid) IS NULL OR loc.id = :loc)
                          AND (CAST(:p AS uuid) IS NULL OR p.id = :p)
                          AND (CAST(:lot AS uuid) IS NULL OR l.id = :lot)
                          AND (CAST(:q AS varchar) IS NULL OR l.quality_status = :q)
                          AND (CAST(:like AS varchar) IS NULL OR lower(p.sku) LIKE :like OR lower(p.name) LIKE :like
                               OR lower(l.lot_number) LIKE :like)
                        ORDER BY p.sku, s.code, loc.code, l.lot_number
                        LIMIT :limit OFFSET :offset
                        """)
                .param("c", companyId).param("site", f.siteId()).param("w", f.warehouseId())
                .param("loc", f.locationId()).param("p", f.productId()).param("lot", f.lotId())
                .param("q", f.quality()).param("like", like).param("limit", limit).param("offset", offset)
                .query((rs, n) -> new StockLine(rs.getObject("product_id", UUID.class), rs.getString("sku"),
                        rs.getString("name"), rs.getString("category"), rs.getString("base_unit"),
                        rs.getObject("lot_id", UUID.class), rs.getString("lot_number"),
                        rs.getString("quality_status"), rs.getString("public_code"),
                        rs.getObject("location_id", UUID.class), rs.getString("location_code"),
                        rs.getString("location_type"), rs.getObject("site_id", UUID.class), rs.getString("site_code"),
                        rs.getBigDecimal("quantity"), rs.getObject("updated_at", OffsetDateTime.class)))
                .list();
    }

    /** Every active product, with its stock even when zero, in one pass over balances and reservations. */
    public List<ProductStock> products(UUID companyId, UUID siteId, String search) {
        String like = search == null || search.isBlank() ? null : "%" + search.strip().toLowerCase() + "%";
        return jdbc.sql("""
                        WITH stock AS (
                          SELECT b.product_id,
                                 SUM(b.quantity) FILTER (WHERE loc.type <> 'TRANSIT') AS physical,
                                 SUM(b.quantity) FILTER (WHERE loc.type <> 'TRANSIT'
                                     AND (l.quality_status <> 'RELEASED' OR loc.type = 'QUARANTINE')) AS blocked,
                                 SUM(b.quantity) FILTER (WHERE loc.type = 'TRANSIT') AS transit
                          FROM wms_balance b
                          JOIN wms_lot l ON l.id = b.lot_id
                          JOIN wms_location loc ON loc.id = b.location_id
                          WHERE b.company_id = :c AND b.quantity > 0
                            AND (CAST(:site AS uuid) IS NULL OR loc.site_id = :site)
                          GROUP BY b.product_id
                        ), reserved AS (
                          SELECT product_id, SUM(quantity - consumed_quantity) AS reserved FROM wms_reservation
                          WHERE company_id = :c AND status = 'ACTIVE'
                            AND (CAST(:site AS uuid) IS NULL OR site_id = :site)
                          GROUP BY product_id
                        )
                        SELECT p.id, p.sku, p.name, p.category, p.base_unit, p.alert_threshold,
                               COALESCE(s.physical, 0) AS physical, COALESCE(s.blocked, 0) AS blocked,
                               COALESCE(r.reserved, 0) AS reserved, COALESCE(s.transit, 0) AS transit
                        FROM wms_product p
                        LEFT JOIN stock s ON s.product_id = p.id
                        LEFT JOIN reserved r ON r.product_id = p.id
                        WHERE p.company_id = :c AND p.active
                          AND (CAST(:like AS varchar) IS NULL OR lower(p.sku) LIKE :like OR lower(p.name) LIKE :like)
                        ORDER BY p.category, p.sku
                        """)
                .param("c", companyId).param("site", siteId).param("like", like)
                .query((rs, n) -> {
                    BigDecimal physical = rs.getBigDecimal("physical");
                    BigDecimal blocked = rs.getBigDecimal("blocked");
                    BigDecimal reserved = rs.getBigDecimal("reserved");
                    BigDecimal available = physical.subtract(blocked).subtract(reserved);
                    BigDecimal threshold = rs.getBigDecimal("alert_threshold");
                    return new ProductStock(rs.getObject("id", UUID.class), rs.getString("sku"), rs.getString("name"),
                            rs.getString("category"), rs.getString("base_unit"), physical, blocked, reserved,
                            available, rs.getBigDecimal("transit"), threshold,
                            threshold != null && available.compareTo(threshold) < 0);
                })
                .list();
    }

    /** Lots this lot was made from (upstream) and lots made from it (downstream), all generations. */
    public Genealogy genealogy(UUID companyId, UUID lotId) {
        return new Genealogy(walk(companyId, lotId, true), walk(companyId, lotId, false));
    }

    private List<GenealogyNode> walk(UUID companyId, UUID lotId, boolean upstream) {
        String next = upstream ? "k.parent_lot_id" : "k.child_lot_id";
        String join = upstream ? "k.child_lot_id" : "k.parent_lot_id";
        return new ArrayList<>(jdbc.sql("""
                        WITH RECURSIVE tree(lot_id, depth, path) AS (
                          SELECT %1$s, 1, ARRAY[CAST(:lot AS uuid), %1$s] FROM wms_lot_link k
                          WHERE k.company_id = :c AND %2$s = CAST(:lot AS uuid)
                          UNION
                          SELECT %1$s, t.depth + 1, t.path || %1$s FROM wms_lot_link k
                          JOIN tree t ON %2$s = t.lot_id
                          WHERE k.company_id = :c AND NOT %1$s = ANY(t.path) AND t.depth < 20
                        )
                        SELECT DISTINCT ON (l.id) l.id, l.lot_number, l.product_id, p.sku, p.name, l.quality_status,
                               l.origin, l.work_order_id, t.depth
                        FROM tree t JOIN wms_lot l ON l.id = t.lot_id JOIN wms_product p ON p.id = l.product_id
                        ORDER BY l.id, t.depth
                        """.formatted(next, join))
                .param("c", companyId).param("lot", lotId)
                .query((rs, n) -> new GenealogyNode(rs.getObject("id", UUID.class), rs.getString("lot_number"),
                        rs.getObject("product_id", UUID.class), rs.getString("sku"), rs.getString("name"),
                        rs.getString("quality_status"), rs.getString("origin"),
                        rs.getObject("work_order_id", UUID.class), rs.getInt("depth")))
                .list()).stream().sorted(java.util.Comparator.comparingInt(GenealogyNode::depth)
                .thenComparing(GenealogyNode::sku)).toList();
    }
}
