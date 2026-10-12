package ma.mystix.gpao;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import ma.mystix.wms.StockViews;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/** Operational dashboard of Atelys (ADR-0014): work orders, production of the last days, quality and stock alerts. */
@Service
public class ProductionDashboard {

    private final JdbcClient jdbc;
    private final WorkOrders workOrders;
    private final StockViews stock;
    private final Clock clock;

    ProductionDashboard(JdbcClient jdbc, WorkOrders workOrders, StockViews stock, Clock clock) {
        this.jdbc = jdbc;
        this.workOrders = workOrders;
        this.stock = stock;
        this.clock = clock;
    }

    /** Production of one business day (Africa/Casablanca), in the made products' base units. */
    public record Day(LocalDate day, BigDecimal good, BigDecimal scrap) {
    }

    public record Dashboard(Map<String, Integer> workOrdersByStatus, List<WorkOrders.WorkOrder> active,
                            List<Day> lastDays, int lotsPendingQuality, int lotsBlocked,
                            List<StockViews.ProductStock> belowThreshold, OffsetDateTime at) {
    }

    public Dashboard dashboard(UUID companyId, UUID siteId) {
        Map<String, Integer> byStatus = new LinkedHashMap<>();
        jdbc.sql("""
                        SELECT status, COUNT(*) AS n FROM gpao_work_order
                        WHERE company_id = :c AND (CAST(:site AS uuid) IS NULL OR site_id = :site)
                        GROUP BY status
                        """)
                .param("c", companyId).param("site", siteId)
                .query(rs -> {
                    byStatus.put(rs.getString("status"), rs.getInt("n"));
                });
        List<WorkOrders.WorkOrder> active = workOrders.list(companyId, null, null, 50).stream()
                .filter(w -> List.of("IN_PROGRESS", "RELEASED", "ON_HOLD").contains(w.status()))
                .filter(w -> siteId == null || siteId.equals(w.siteId())).limit(8).toList();
        List<Day> days = jdbc.sql("""
                        SELECT (r.reported_at AT TIME ZONE 'Africa/Casablanca')::date AS day,
                               SUM(r.good_quantity) AS good, SUM(r.scrap_quantity) AS scrap
                        FROM gpao_production_report r
                        JOIN gpao_work_order w ON w.id = r.work_order_id
                        WHERE r.company_id = :c AND r.reported_at >= :since
                          AND (CAST(:site AS uuid) IS NULL OR w.site_id = :site)
                        GROUP BY 1 ORDER BY 1
                        """)
                .param("c", companyId).param("site", siteId)
                .param("since", OffsetDateTime.now(clock).minusDays(7))
                .query((rs, n) -> new Day(rs.getObject("day", LocalDate.class), rs.getBigDecimal("good"),
                        rs.getBigDecimal("scrap")))
                .list();
        Map<String, Integer> quality = new LinkedHashMap<>();
        jdbc.sql("""
                        SELECT l.quality_status, COUNT(DISTINCT l.id) AS n FROM wms_lot l
                        JOIN wms_balance b ON b.lot_id = l.id AND b.quantity > 0
                        JOIN wms_location loc ON loc.id = b.location_id
                        WHERE l.company_id = :c AND l.quality_status IN ('PENDING', 'BLOCKED')
                          AND (CAST(:site AS uuid) IS NULL OR loc.site_id = :site)
                        GROUP BY l.quality_status
                        """)
                .param("c", companyId).param("site", siteId)
                .query(rs -> {
                    quality.put(rs.getString("quality_status"), rs.getInt("n"));
                });
        List<StockViews.ProductStock> below = stock.products(companyId, siteId, null).stream()
                .filter(StockViews.ProductStock::belowThreshold).toList();
        return new Dashboard(byStatus, active, days, quality.getOrDefault("PENDING", 0),
                quality.getOrDefault("BLOCKED", 0), below, OffsetDateTime.now(clock));
    }
}
