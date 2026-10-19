package ma.mystix.wms;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import ma.mystix.shared.Gtin;
import ma.mystix.shared.error.ErrorCode;
import ma.mystix.shared.error.MystixException;
import ma.mystix.tenant.CompanyService;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Atelys referentials (ADR-0014): units, products and their explicit unit conversions, sites, warehouses and
 * locations. Every site gets a TRANSIT location for the stock shipped to it and not yet received.
 */
@Service
public class WmsCatalog {

    public static final List<String> CATEGORIES = List.of("RAW_MATERIAL", "COMPONENT", "CONSUMABLE", "BY_PRODUCT",
            "SEMI_FINISHED", "FINISHED");
    public static final List<String> LOCATION_TYPES = List.of("RECEIVING", "STORAGE", "PRODUCTION", "QUARANTINE",
            "SHIPPING");

    private final JdbcClient jdbc;
    private final CompanyService companies;
    private final Clock clock;

    WmsCatalog(JdbcClient jdbc, CompanyService companies, Clock clock) {
        this.jdbc = jdbc;
        this.companies = companies;
        this.clock = clock;
    }

    public record Unit(String code, String name, String dimension) {
    }

    /**
     * @param receiptQuality quality status of a new lot of this product: PENDING (to be released) or RELEASED
     * @param conversions    unit code → how many base units one such unit is worth
     */
    public record Product(UUID id, String sku, String name, String description, String category, String baseUnit,
                          boolean lotTracked, String receiptQuality, BigDecimal alertThreshold, boolean active,
                          Map<String, BigDecimal> conversions, String gtin) {
    }

    public record NewProduct(String sku, String name, String description, String category, String baseUnit,
                             boolean lotTracked, String receiptQuality, BigDecimal alertThreshold, String gtin) {

        public NewProduct(String sku, String name, String description, String category, String baseUnit,
                          boolean lotTracked, String receiptQuality, BigDecimal alertThreshold) {
            this(sku, name, description, category, baseUnit, lotTracked, receiptQuality, alertThreshold, null);
        }
    }

    public record Site(UUID id, String code, String name, boolean active, UUID transitLocationId) {
    }

    public record Warehouse(UUID id, UUID siteId, String code, String name, boolean active) {
    }

    public record Location(UUID id, UUID siteId, UUID warehouseId, String code, String zone, String type,
                           boolean active) {
    }

    // ---------- units ----------

    @Transactional
    public Unit createUnit(UUID companyId, String code, String name, String dimension) {
        companies.get(companyId);
        Unit unit = new Unit(code.strip().toUpperCase(), name.strip(), dimension);
        insert(() -> jdbc.sql("INSERT INTO wms_unit (company_id, code, name, dimension) VALUES (:c, :code, :name, :d)")
                .param("c", companyId).param("code", unit.code()).param("name", unit.name())
                .param("d", unit.dimension()).update(), "unit " + unit.code());
        return unit;
    }

    public List<Unit> units(UUID companyId) {
        return jdbc.sql("SELECT code, name, dimension FROM wms_unit WHERE company_id = :c ORDER BY code")
                .param("c", companyId)
                .query((rs, n) -> new Unit(rs.getString("code"), rs.getString("name"), rs.getString("dimension")))
                .list();
    }

    // ---------- products ----------

    @Transactional
    public Product createProduct(UUID companyId, NewProduct p) {
        companies.get(companyId);
        unitExists(companyId, p.baseUnit());
        UUID id = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now(clock);
        insert(() -> jdbc.sql("""
                        INSERT INTO wms_product (id, company_id, sku, name, description, category, base_unit,
                                                 lot_tracked, receipt_quality, alert_threshold, active, created_at,
                                                 updated_at, gtin)
                        VALUES (:id, :c, :sku, :name, :description, :category, :unit, :lot, :quality, :threshold,
                                TRUE, :now, :now, :gtin)
                        """)
                .param("id", id).param("c", companyId).param("sku", p.sku().strip())
                .param("name", p.name().strip()).param("description", p.description())
                .param("category", p.category()).param("unit", p.baseUnit().strip().toUpperCase())
                .param("lot", p.lotTracked()).param("quality", p.receiptQuality())
                .param("threshold", p.alertThreshold()).param("now", now).param("gtin", gtin14(p.gtin()))
                .update(), "product " + p.sku());
        return product(companyId, id);
    }

    /** Sets or removes (blank) the GTIN of a product; the check digit is verified, never corrected. */
    @Transactional
    public Product setGtin(UUID companyId, UUID productId, String gtin) {
        product(companyId, productId);
        String value = gtin == null || gtin.isBlank() ? null : gtin14(gtin);
        insert(() -> jdbc.sql("UPDATE wms_product SET gtin = :g, updated_at = :now WHERE company_id = :c AND id = :id")
                .param("g", value).param("now", OffsetDateTime.now(clock)).param("c", companyId)
                .param("id", productId).update(), "GTIN " + gtin);
        return product(companyId, productId);
    }

    /** The product of this environment carrying the scanned code, if any. */
    public java.util.Optional<Product> productByGtin(UUID companyId, String code) {
        String g = gtin14(code);
        return jdbc.sql("SELECT id FROM wms_product WHERE company_id = :c AND gtin = :g")
                .param("c", companyId).param("g", g).query(UUID.class).optional().map(id -> product(companyId, id));
    }

    private static String gtin14(String code) {
        if (code == null || code.isBlank()) {
            return null;
        }
        return Gtin.parse(code).map(Gtin::gtin14).orElseThrow(() -> new MystixException(ErrorCode.GTIN_INVALID,
                "Not a GTIN with a valid check digit: " + code));
    }

    /** One {@code unit} is worth {@code factor} base units of the product. */
    @Transactional
    public Product setConversion(UUID companyId, UUID productId, String unit, BigDecimal factor, String note) {
        Product p = product(companyId, productId);
        String code = unit.strip().toUpperCase();
        unitExists(companyId, code);
        if (code.equals(p.baseUnit())) {
            throw new MystixException(ErrorCode.UNIT_INCOMPATIBLE, "The base unit needs no conversion");
        }
        jdbc.sql("""
                        INSERT INTO wms_unit_conversion (company_id, product_id, unit, factor, note)
                        VALUES (:c, :p, :u, :f, :note)
                        ON CONFLICT (product_id, unit) DO UPDATE SET factor = EXCLUDED.factor, note = EXCLUDED.note
                        """)
                .param("c", companyId).param("p", productId).param("u", code).param("f", factor)
                .param("note", note).update();
        return product(companyId, productId);
    }

    public Product product(UUID companyId, UUID productId) {
        return products(companyId, productId, null, null).stream().findFirst()
                .orElseThrow(() -> notFound("product " + productId));
    }

    public List<Product> products(UUID companyId, String category, String search) {
        return products(companyId, null, category, search);
    }

    private List<Product> products(UUID companyId, UUID productId, String category, String search) {
        String like = search == null || search.isBlank() ? null : "%" + search.strip().toLowerCase() + "%";
        List<Product> rows = jdbc.sql("""
                        SELECT * FROM wms_product
                        WHERE company_id = :c
                          AND (CAST(:id AS uuid) IS NULL OR id = :id)
                          AND (CAST(:category AS varchar) IS NULL OR category = :category)
                          AND (CAST(:like AS varchar) IS NULL OR lower(sku) LIKE :like OR lower(name) LIKE :like
                               OR gtin = :gtin)
                        ORDER BY sku
                        """)
                .param("c", companyId).param("id", productId).param("category", category).param("like", like)
                .param("gtin", search == null ? null : Gtin.parse(search).map(Gtin::gtin14).orElse(null))
                .query((rs, n) -> new Product(rs.getObject("id", UUID.class), rs.getString("sku"),
                        rs.getString("name"), rs.getString("description"), rs.getString("category"),
                        rs.getString("base_unit"), rs.getBoolean("lot_tracked"), rs.getString("receipt_quality"),
                        rs.getBigDecimal("alert_threshold"), rs.getBoolean("active"), Map.of(), rs.getString("gtin")))
                .list();
        if (rows.isEmpty()) {
            return rows;
        }
        Map<UUID, Map<String, BigDecimal>> conversions = new LinkedHashMap<>();
        jdbc.sql("SELECT product_id, unit, factor FROM wms_unit_conversion WHERE company_id = :c ORDER BY unit")
                .param("c", companyId)
                .query(rs -> {
                    conversions.computeIfAbsent(rs.getObject("product_id", UUID.class), k -> new LinkedHashMap<>())
                            .put(rs.getString("unit"), rs.getBigDecimal("factor").stripTrailingZeros());
                });
        return rows.stream().map(p -> new Product(p.id(), p.sku(), p.name(), p.description(), p.category(),
                p.baseUnit(), p.lotTracked(), p.receiptQuality(), p.alertThreshold(), p.active(),
                Map.copyOf(conversions.getOrDefault(p.id(), Map.of())), p.gtin())).toList();
    }

    /**
     * A quantity in the product's base unit, rounded to 3 decimals. Only explicit conversions are applied: an unknown
     * unit is refused, never guessed.
     */
    public BigDecimal toBase(Product product, BigDecimal quantity, String unit) {
        if (quantity == null || quantity.signum() <= 0) {
            throw new MystixException(ErrorCode.VALIDATION_FAILED, "Quantity must be positive");
        }
        String code = unit == null || unit.isBlank() ? product.baseUnit() : unit.strip().toUpperCase();
        if (code.equals(product.baseUnit())) {
            return quantity.setScale(3, RoundingMode.HALF_UP);
        }
        BigDecimal factor = product.conversions().get(code);
        if (factor == null) {
            throw new MystixException(ErrorCode.UNIT_INCOMPATIBLE,
                    "No conversion from " + code + " to " + product.baseUnit() + " for " + product.sku());
        }
        return quantity.multiply(factor).setScale(3, RoundingMode.HALF_UP);
    }

    // ---------- sites, warehouses, locations ----------

    @Transactional
    public Site createSite(UUID companyId, String code, String name) {
        companies.get(companyId);
        UUID id = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now(clock);
        insert(() -> jdbc.sql("""
                        INSERT INTO wms_site (id, company_id, code, name, active, created_at)
                        VALUES (:id, :c, :code, :name, TRUE, :now)
                        """)
                .param("id", id).param("c", companyId).param("code", code.strip().toUpperCase())
                .param("name", name.strip()).param("now", now).update(), "site " + code);
        jdbc.sql("""
                        INSERT INTO wms_location (id, company_id, site_id, warehouse_id, code, zone, type, active,
                                                  created_at)
                        VALUES (:id, :c, :site, NULL, 'TRANSIT', NULL, 'TRANSIT', TRUE, :now)
                        """)
                .param("id", UUID.randomUUID()).param("c", companyId).param("site", id).param("now", now).update();
        return site(companyId, id);
    }

    public List<Site> sites(UUID companyId) {
        return jdbc.sql("""
                        SELECT s.*, l.id AS transit_id FROM wms_site s
                        JOIN wms_location l ON l.site_id = s.id AND l.type = 'TRANSIT'
                        WHERE s.company_id = :c ORDER BY s.code
                        """)
                .param("c", companyId)
                .query((rs, n) -> new Site(rs.getObject("id", UUID.class), rs.getString("code"), rs.getString("name"),
                        rs.getBoolean("active"), rs.getObject("transit_id", UUID.class)))
                .list();
    }

    public Site site(UUID companyId, UUID siteId) {
        return sites(companyId).stream().filter(s -> s.id().equals(siteId)).findFirst()
                .orElseThrow(() -> notFound("site " + siteId));
    }

    @Transactional
    public Warehouse createWarehouse(UUID companyId, UUID siteId, String code, String name) {
        site(companyId, siteId);
        UUID id = UUID.randomUUID();
        insert(() -> jdbc.sql("""
                        INSERT INTO wms_warehouse (id, company_id, site_id, code, name, active, created_at)
                        VALUES (:id, :c, :site, :code, :name, TRUE, :now)
                        """)
                .param("id", id).param("c", companyId).param("site", siteId)
                .param("code", code.strip().toUpperCase()).param("name", name.strip())
                .param("now", OffsetDateTime.now(clock)).update(), "warehouse " + code);
        return new Warehouse(id, siteId, code.strip().toUpperCase(), name.strip(), true);
    }

    public List<Warehouse> warehouses(UUID companyId, UUID siteId) {
        return jdbc.sql("""
                        SELECT * FROM wms_warehouse
                        WHERE company_id = :c AND (CAST(:site AS uuid) IS NULL OR site_id = :site)
                        ORDER BY code
                        """)
                .param("c", companyId).param("site", siteId)
                .query((rs, n) -> new Warehouse(rs.getObject("id", UUID.class), rs.getObject("site_id", UUID.class),
                        rs.getString("code"), rs.getString("name"), rs.getBoolean("active")))
                .list();
    }

    public Warehouse warehouse(UUID companyId, UUID warehouseId) {
        return warehouses(companyId, null).stream().filter(w -> w.id().equals(warehouseId)).findFirst()
                .orElseThrow(() -> notFound("warehouse " + warehouseId));
    }

    @Transactional
    public Location createLocation(UUID companyId, UUID warehouseId, String code, String zone, String type) {
        Warehouse w = warehouse(companyId, warehouseId);
        if (!LOCATION_TYPES.contains(type)) {
            throw new MystixException(ErrorCode.VALIDATION_FAILED, "Unknown location type " + type);
        }
        UUID id = UUID.randomUUID();
        insert(() -> jdbc.sql("""
                        INSERT INTO wms_location (id, company_id, site_id, warehouse_id, code, zone, type, active,
                                                  created_at)
                        VALUES (:id, :c, :site, :w, :code, :zone, :type, TRUE, :now)
                        """)
                .param("id", id).param("c", companyId).param("site", w.siteId()).param("w", warehouseId)
                .param("code", code.strip().toUpperCase()).param("zone", zone).param("type", type)
                .param("now", OffsetDateTime.now(clock)).update(), "location " + code);
        return location(companyId, id);
    }

    public List<Location> locations(UUID companyId, UUID siteId, UUID warehouseId) {
        return jdbc.sql("""
                        SELECT * FROM wms_location
                        WHERE company_id = :c
                          AND (CAST(:site AS uuid) IS NULL OR site_id = :site)
                          AND (CAST(:w AS uuid) IS NULL OR warehouse_id = :w)
                        ORDER BY type = 'TRANSIT', code
                        """)
                .param("c", companyId).param("site", siteId).param("w", warehouseId)
                .query((rs, n) -> new Location(rs.getObject("id", UUID.class), rs.getObject("site_id", UUID.class),
                        rs.getObject("warehouse_id", UUID.class), rs.getString("code"), rs.getString("zone"),
                        rs.getString("type"), rs.getBoolean("active")))
                .list();
    }

    public Location location(UUID companyId, UUID locationId) {
        return jdbc.sql("SELECT * FROM wms_location WHERE company_id = :c AND id = :id")
                .param("c", companyId).param("id", locationId)
                .query((rs, n) -> new Location(rs.getObject("id", UUID.class), rs.getObject("site_id", UUID.class),
                        rs.getObject("warehouse_id", UUID.class), rs.getString("code"), rs.getString("zone"),
                        rs.getString("type"), rs.getBoolean("active")))
                .optional().orElseThrow(() -> notFound("location " + locationId));
    }

    private void unitExists(UUID companyId, String code) {
        boolean exists = jdbc.sql("SELECT EXISTS (SELECT 1 FROM wms_unit WHERE company_id = :c AND code = :code)")
                .param("c", companyId).param("code", code.strip().toUpperCase()).query(Boolean.class).single();
        if (!exists) {
            throw new MystixException(ErrorCode.UNIT_INCOMPATIBLE, "Unknown unit " + code);
        }
    }

    static MystixException notFound(String what) {
        return new MystixException(ErrorCode.ATELYS_NOT_FOUND, "Not found: " + what);
    }

    private static void insert(Runnable insert, String what) {
        try {
            insert.run();
        } catch (DuplicateKeyException e) {
            throw new MystixException(ErrorCode.ATELYS_CODE_TAKEN, "Code taken: " + what, e);
        } catch (DataIntegrityViolationException e) {
            throw new MystixException(ErrorCode.VALIDATION_FAILED, "Invalid " + what + ": " + e.getMostSpecificCause()
                    .getMessage(), e);
        }
    }
}
