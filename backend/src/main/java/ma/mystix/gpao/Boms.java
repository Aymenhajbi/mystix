package ma.mystix.gpao;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;

import ma.mystix.shared.error.ApiError;
import ma.mystix.shared.error.ErrorCode;
import ma.mystix.shared.error.MystixException;
import ma.mystix.wms.AtelysAudit;
import ma.mystix.wms.WmsCatalog;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Versioned bills of materials (ADR-0014). A version is created as DRAFT and becomes VALIDATED by a manager; validating
 * a version makes the previously validated one of the same product OBSOLETE. Nothing OPAB-specific is coded: recipes,
 * quantities and losses are data.
 */
@Service
public class Boms {

    private final JdbcClient jdbc;
    private final WmsCatalog catalog;
    private final AtelysAudit audit;
    private final Clock clock;

    Boms(JdbcClient jdbc, WmsCatalog catalog, AtelysAudit audit, Clock clock) {
        this.jdbc = jdbc;
        this.catalog = catalog;
        this.audit = audit;
        this.clock = clock;
    }

    /** {@code quantity} of the component, in its base unit, for the BOM's reference quantity of the made product. */
    public record Line(int lineNo, UUID componentId, String sku, String name, String unit, BigDecimal quantity,
                       BigDecimal lossPercent, boolean substitutionAllowed) {
    }

    public record Operation(int sequence, String name, String workstation, BigDecimal standardMinutes,
                            String instructions, String qualityCheck) {
    }

    public record Bom(UUID id, UUID productId, String sku, String productName, String unit, int version,
                      BigDecimal referenceQuantity, String status, LocalDate validFrom, LocalDate validTo,
                      String note, OffsetDateTime createdAt, String createdBy, OffsetDateTime validatedAt,
                      String validatedBy, List<Line> lines, List<Operation> operations) {
    }

    public record NewLine(UUID componentId, BigDecimal quantity, BigDecimal lossPercent, boolean substitutionAllowed) {
    }

    public record NewBom(UUID productId, BigDecimal referenceQuantity, LocalDate validFrom, LocalDate validTo,
                         String note, List<NewLine> lines, List<Operation> operations) {
    }

    /** What a quantity of the made product needs of one component, loss included. */
    public record Requirement(UUID componentId, String sku, String name, String unit, BigDecimal quantity) {
    }

    @Transactional
    public Bom create(UUID companyId, NewBom b, String actor) {
        WmsCatalog.Product product = catalog.product(companyId, b.productId());
        List<ApiError.FieldViolation> problems = new ArrayList<>();
        if (b.referenceQuantity() == null || b.referenceQuantity().signum() <= 0) {
            problems.add(new ApiError.FieldViolation("referenceQuantity", "must be positive"));
        }
        if (b.lines() == null || b.lines().isEmpty()) {
            problems.add(new ApiError.FieldViolation("lines", "at least one component"));
        } else {
            var seen = new HashSet<UUID>();
            for (int i = 0; i < b.lines().size(); i++) {
                NewLine l = b.lines().get(i);
                if (product.id().equals(l.componentId())) {
                    problems.add(new ApiError.FieldViolation("lines[" + i + "].componentId", "is the made product"));
                }
                if (!seen.add(l.componentId())) {
                    problems.add(new ApiError.FieldViolation("lines[" + i + "].componentId", "appears twice"));
                }
                if (l.quantity() == null || l.quantity().signum() <= 0) {
                    problems.add(new ApiError.FieldViolation("lines[" + i + "].quantity", "must be positive"));
                }
                BigDecimal loss = l.lossPercent() == null ? BigDecimal.ZERO : l.lossPercent();
                if (loss.signum() < 0 || loss.compareTo(BigDecimal.valueOf(100)) >= 0) {
                    problems.add(new ApiError.FieldViolation("lines[" + i + "].lossPercent", "0 to 99.999"));
                }
            }
        }
        if (!problems.isEmpty()) {
            throw new MystixException(ErrorCode.BOM_INVALID, "Invalid BOM", problems, List.of(), null);
        }
        b.lines().forEach(l -> catalog.product(companyId, l.componentId()));
        int version = jdbc.sql("SELECT COALESCE(MAX(version), 0) + 1 FROM gpao_bom WHERE company_id = :c AND product_id = :p")
                .param("c", companyId).param("p", product.id()).query(Integer.class).single();
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                        INSERT INTO gpao_bom (id, company_id, product_id, version, reference_quantity, status,
                                              valid_from, valid_to, note, created_at, created_by)
                        VALUES (:id, :c, :p, :v, :ref, 'DRAFT', :from, :to, :note, :now, :actor)
                        """)
                .param("id", id).param("c", companyId).param("p", product.id()).param("v", version)
                .param("ref", b.referenceQuantity()).param("from", b.validFrom()).param("to", b.validTo())
                .param("note", b.note()).param("now", OffsetDateTime.now(clock)).param("actor", actor).update();
        int no = 0;
        for (NewLine l : b.lines()) {
            jdbc.sql("""
                            INSERT INTO gpao_bom_line (bom_id, line_no, component_id, quantity, loss_percent,
                                                       substitution_allowed)
                            VALUES (:b, :no, :comp, :q, :loss, :sub)
                            """)
                    .param("b", id).param("no", ++no).param("comp", l.componentId()).param("q", l.quantity())
                    .param("loss", l.lossPercent() == null ? BigDecimal.ZERO : l.lossPercent())
                    .param("sub", l.substitutionAllowed()).update();
        }
        if (b.operations() != null) {
            for (Operation o : b.operations()) {
                jdbc.sql("""
                                INSERT INTO gpao_bom_operation (bom_id, sequence, name, workstation, standard_minutes,
                                                                instructions, quality_check)
                                VALUES (:b, :s, :name, :ws, :min, :ins, :qc)
                                """)
                        .param("b", id).param("s", o.sequence()).param("name", o.name())
                        .param("ws", o.workstation()).param("min", o.standardMinutes())
                        .param("ins", o.instructions()).param("qc", o.qualityCheck()).update();
            }
        }
        return get(companyId, id);
    }

    /** DRAFT → VALIDATED (manager); the product's previous validated version becomes OBSOLETE. */
    @Transactional
    public Bom validate(UUID companyId, UUID bomId, String actor) {
        Bom bom = get(companyId, bomId);
        if (!"DRAFT".equals(bom.status())) {
            throw new MystixException(ErrorCode.STATUS_TRANSITION_INVALID, "BOM is " + bom.status());
        }
        OffsetDateTime now = OffsetDateTime.now(clock);
        jdbc.sql("""
                        UPDATE gpao_bom SET status = 'OBSOLETE'
                        WHERE company_id = :c AND product_id = :p AND status = 'VALIDATED'
                        """)
                .param("c", companyId).param("p", bom.productId()).update();
        jdbc.sql("UPDATE gpao_bom SET status = 'VALIDATED', validated_at = :now, validated_by = :a WHERE id = :id")
                .param("now", now).param("a", actor).param("id", bomId).update();
        audit.record(companyId, "BOM_VALIDATED", "BOM", bomId, actor, null, bom.sku() + " v" + bom.version());
        return get(companyId, bomId);
    }

    @Transactional
    public Bom obsolete(UUID companyId, UUID bomId, String actor) {
        Bom bom = get(companyId, bomId);
        if ("OBSOLETE".equals(bom.status())) {
            return bom;
        }
        jdbc.sql("UPDATE gpao_bom SET status = 'OBSOLETE' WHERE id = :id").param("id", bomId).update();
        audit.record(companyId, "BOM_OBSOLETE", "BOM", bomId, actor, null, bom.sku() + " v" + bom.version());
        return get(companyId, bomId);
    }

    /** Requirement per component for {@code quantity} of the made product: qty × quantity / reference × (1 + loss). */
    public List<Requirement> requirements(Bom bom, BigDecimal quantity) {
        return bom.lines().stream().map(l -> new Requirement(l.componentId(), l.sku(), l.name(), l.unit(),
                l.quantity().multiply(quantity)
                        .multiply(BigDecimal.ONE.add(l.lossPercent().movePointLeft(2)))
                        .divide(bom.referenceQuantity(), 3, RoundingMode.HALF_UP))).toList();
    }

    public Bom get(UUID companyId, UUID bomId) {
        Bom head = list(companyId, null, bomId).stream().findFirst()
                .orElseThrow(() -> new MystixException(ErrorCode.ATELYS_NOT_FOUND, "BOM " + bomId));
        List<Line> lines = jdbc.sql("""
                        SELECT bl.*, p.sku, p.name, p.base_unit FROM gpao_bom_line bl
                        JOIN wms_product p ON p.id = bl.component_id
                        WHERE bl.bom_id = :b ORDER BY bl.line_no
                        """)
                .param("b", bomId)
                .query((rs, n) -> new Line(rs.getInt("line_no"), rs.getObject("component_id", UUID.class),
                        rs.getString("sku"), rs.getString("name"), rs.getString("base_unit"),
                        rs.getBigDecimal("quantity").stripTrailingZeros(),
                        rs.getBigDecimal("loss_percent").stripTrailingZeros(), rs.getBoolean("substitution_allowed")))
                .list();
        List<Operation> ops = jdbc.sql("SELECT * FROM gpao_bom_operation WHERE bom_id = :b ORDER BY sequence")
                .param("b", bomId)
                .query((rs, n) -> new Operation(rs.getInt("sequence"), rs.getString("name"),
                        rs.getString("workstation"), rs.getBigDecimal("standard_minutes"),
                        rs.getString("instructions"), rs.getString("quality_check")))
                .list();
        return new Bom(head.id(), head.productId(), head.sku(), head.productName(), head.unit(), head.version(),
                head.referenceQuantity(), head.status(), head.validFrom(), head.validTo(), head.note(),
                head.createdAt(), head.createdBy(), head.validatedAt(), head.validatedBy(), lines, ops);
    }

    /** The validated version in force for a product, if any. */
    public Bom active(UUID companyId, UUID productId) {
        return list(companyId, productId, null).stream().filter(b -> "VALIDATED".equals(b.status())).findFirst()
                .map(b -> get(companyId, b.id())).orElse(null);
    }

    public List<Bom> list(UUID companyId, UUID productId, UUID bomId) {
        return jdbc.sql("""
                        SELECT b.*, p.sku, p.name, p.base_unit FROM gpao_bom b JOIN wms_product p ON p.id = b.product_id
                        WHERE b.company_id = :c AND (CAST(:p AS uuid) IS NULL OR b.product_id = :p)
                          AND (CAST(:id AS uuid) IS NULL OR b.id = :id)
                        ORDER BY p.sku, b.version DESC
                        """)
                .param("c", companyId).param("p", productId).param("id", bomId)
                .query((rs, n) -> new Bom(rs.getObject("id", UUID.class), rs.getObject("product_id", UUID.class),
                        rs.getString("sku"), rs.getString("name"), rs.getString("base_unit"), rs.getInt("version"),
                        rs.getBigDecimal("reference_quantity"), rs.getString("status"),
                        rs.getObject("valid_from", LocalDate.class), rs.getObject("valid_to", LocalDate.class),
                        rs.getString("note"), rs.getObject("created_at", OffsetDateTime.class),
                        rs.getString("created_by"), rs.getObject("validated_at", OffsetDateTime.class),
                        rs.getString("validated_by"), List.of(), List.of()))
                .list();
    }
}
