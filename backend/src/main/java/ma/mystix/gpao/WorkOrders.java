package ma.mystix.gpao;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import ma.mystix.shared.Sha256;
import ma.mystix.shared.error.ErrorCode;
import ma.mystix.shared.error.MystixException;
import ma.mystix.wms.AtelysAudit;
import ma.mystix.wms.Ledger;
import ma.mystix.wms.Lots;
import ma.mystix.wms.Reservations;
import ma.mystix.wms.WmsCatalog;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Work orders (ADR-0014): DRAFT → PLANNED → RELEASED → IN_PROGRESS → COMPLETED → CLOSED, with ON_HOLD and CANCELLED.
 * Materials are issued lot by lot, production is declared in partial reports; every stock effect is a movement of
 * the WMS ledger in the same transaction as the GPAO record, and a retried declaration counts once.
 */
@Service
public class WorkOrders {

    /** Allowed transitions; IN_PROGRESS is also entered by the first issue or report of a released order. */
    static final Map<String, Set<String>> TRANSITIONS = Map.of(
            "DRAFT", Set.of("PLANNED", "RELEASED", "CANCELLED"),
            "PLANNED", Set.of("RELEASED", "DRAFT", "CANCELLED"),
            "RELEASED", Set.of("IN_PROGRESS", "ON_HOLD", "CANCELLED"),
            "IN_PROGRESS", Set.of("ON_HOLD", "COMPLETED"),
            "ON_HOLD", Set.of("RELEASED", "IN_PROGRESS", "CANCELLED"),
            "COMPLETED", Set.of("CLOSED", "IN_PROGRESS"),
            "CLOSED", Set.of(),
            "CANCELLED", Set.of());

    private final JdbcClient jdbc;
    private final WmsCatalog catalog;
    private final Lots lots;
    private final Ledger ledger;
    private final Reservations reservations;
    private final Boms boms;
    private final AtelysAudit audit;
    private final Clock clock;
    private final BigDecimal tolerancePercent;

    WorkOrders(JdbcClient jdbc, WmsCatalog catalog, Lots lots, Ledger ledger, Reservations reservations, Boms boms,
               AtelysAudit audit, Clock clock,
               @Value("${mystix.atelys.consumption-tolerance-percent:10}") BigDecimal tolerancePercent) {
        this.jdbc = jdbc;
        this.catalog = catalog;
        this.lots = lots;
        this.ledger = ledger;
        this.reservations = reservations;
        this.boms = boms;
        this.audit = audit;
        this.clock = clock;
        this.tolerancePercent = tolerancePercent;
    }

    public record WorkOrder(UUID id, String number, UUID productId, String sku, String productName, String unit,
                            UUID bomId, int bomVersion, BigDecimal plannedQuantity, UUID siteId,
                            String productionLine, UUID outputLocationId, String outputLocation,
                            LocalDate plannedStart, LocalDate plannedEnd, int priority, String status,
                            String customerReference, BigDecimal producedQuantity, BigDecimal goodQuantity,
                            BigDecimal scrapQuantity, String responsible, OffsetDateTime createdAt,
                            OffsetDateTime updatedAt) {
    }

    public record NewWorkOrder(String number, UUID productId, UUID bomId, BigDecimal plannedQuantity, UUID siteId,
                               String productionLine, UUID outputLocationId, LocalDate plannedStart,
                               LocalDate plannedEnd, Integer priority, String customerReference, String responsible) {
    }

    public record History(String fromStatus, String toStatus, String actor, String reason, OffsetDateTime at) {
    }

    public record Consumption(UUID id, String kind, UUID productId, String sku, UUID lotId, String lotNumber,
                              UUID locationId, String locationCode, BigDecimal quantity, String unit,
                              String justification, String actor, OffsetDateTime at) {
    }

    public record Residue(UUID id, UUID productId, String sku, BigDecimal quantity, String unit, String fate,
                          String destination, UUID lotId, String lotNumber) {
    }

    public record Report(UUID id, String reportKey, BigDecimal goodQuantity, BigDecimal scrapQuantity,
                         String scrapCause, String productionLine, String operator, String comment, UUID outputLotId,
                         String outputLotNumber, OffsetDateTime reportedAt, String actor, List<Residue> residues) {
    }

    /** A material line of the order: need from the BOM, reservations and net issues. */
    public record Material(UUID componentId, String sku, String name, String unit, BigDecimal required,
                           BigDecimal reserved, BigDecimal issued, BigDecimal remaining, BigDecimal available) {
    }

    public record Detail(WorkOrder order, Boms.Bom bom, List<Material> materials, List<Consumption> consumptions,
                         List<Report> reports, List<History> history, Variance variance) {
    }

    /**
     * Indicators with their definitions. Ratios are null when their denominator is zero.
     *
     * @param completion      produced (good + scrap) / planned
     * @param scrapRate       scrap / produced
     * @param components      consumption variance per component: (issued − theoretical for what was produced) /
     *                        theoretical
     */
    public record Variance(BigDecimal planned, BigDecimal produced, BigDecimal good, BigDecimal scrap,
                           BigDecimal completion, BigDecimal scrapRate, List<ComponentVariance> components) {
    }

    public record ComponentVariance(UUID componentId, String sku, String unit, BigDecimal theoretical,
                                    BigDecimal issued, BigDecimal deviation) {
    }

    // ---------- creation and lifecycle ----------

    @Transactional
    public WorkOrder create(UUID companyId, NewWorkOrder w, String actor) {
        WmsCatalog.Product product = catalog.product(companyId, w.productId());
        catalog.site(companyId, w.siteId());
        Boms.Bom bom = w.bomId() == null ? boms.active(companyId, product.id()) : boms.get(companyId, w.bomId());
        if (bom == null || !bom.productId().equals(product.id()) || "OBSOLETE".equals(bom.status())) {
            throw new MystixException(ErrorCode.BOM_NOT_VALIDATED, "No usable BOM for " + product.sku());
        }
        WmsCatalog.Location output = catalog.location(companyId, w.outputLocationId());
        if (!output.siteId().equals(w.siteId()) || "TRANSIT".equals(output.type())) {
            throw new MystixException(ErrorCode.VALIDATION_FAILED, "Output location must be on the order's site");
        }
        if (w.plannedQuantity() == null || w.plannedQuantity().signum() <= 0) {
            throw new MystixException(ErrorCode.VALIDATION_FAILED, "Planned quantity must be positive");
        }
        UUID id = UUID.randomUUID();
        String number = w.number() == null || w.number().isBlank() ? audit.nextNumber(companyId, "OF")
                : w.number().strip();
        OffsetDateTime now = OffsetDateTime.now(clock);
        try {
            jdbc.sql("""
                            INSERT INTO gpao_work_order (id, company_id, number, product_id, bom_id, planned_quantity,
                                                         site_id, production_line, output_location_id, planned_start,
                                                         planned_end, priority, status, customer_reference,
                                                         responsible, created_at, updated_at)
                            VALUES (:id, :c, :n, :p, :bom, :q, :site, :line, :out, :start, :end, :prio, 'DRAFT',
                                    :ref, :resp, :now, :now)
                            """)
                    .param("id", id).param("c", companyId).param("n", number).param("p", product.id())
                    .param("bom", bom.id()).param("q", w.plannedQuantity()).param("site", w.siteId())
                    .param("line", w.productionLine()).param("out", output.id()).param("start", w.plannedStart())
                    .param("end", w.plannedEnd()).param("prio", w.priority() == null ? 3 : w.priority())
                    .param("ref", w.customerReference()).param("resp", w.responsible()).param("now", now).update();
        } catch (DuplicateKeyException e) {
            throw new MystixException(ErrorCode.ATELYS_CODE_TAKEN, "Work order " + number + " exists", e);
        }
        history(id, null, "DRAFT", actor, null, now);
        return get(companyId, id);
    }

    /**
     * Changes the status along {@link #TRANSITIONS}. Release needs a validated BOM; cancel needs an order with nothing
     * issued nor declared; complete needs at least one declaration; close (manager) and cancel release the
     * reservations still active.
     */
    @Transactional
    public WorkOrder transition(UUID companyId, UUID orderId, String target, String reason, String actor) {
        WorkOrder wo = locked(companyId, orderId);
        if (target.equals(wo.status())) {
            return wo;
        }
        if (!TRANSITIONS.getOrDefault(wo.status(), Set.of()).contains(target)) {
            throw new MystixException(ErrorCode.STATUS_TRANSITION_INVALID, wo.number() + ": " + wo.status() + " -> "
                    + target);
        }
        switch (target) {
            case "RELEASED" -> {
                if (!"VALIDATED".equals(boms.get(companyId, wo.bomId()).status())) {
                    throw new MystixException(ErrorCode.BOM_NOT_VALIDATED, wo.number() + " BOM not validated");
                }
            }
            case "CANCELLED" -> {
                if (count("gpao_consumption", orderId) > 0 || count("gpao_production_report", orderId) > 0) {
                    throw new MystixException(ErrorCode.STATUS_TRANSITION_INVALID,
                            wo.number() + " has issues or reports: complete it instead");
                }
                reservations.releaseAll(companyId, orderId);
            }
            case "COMPLETED" -> {
                if (count("gpao_production_report", orderId) == 0) {
                    throw new MystixException(ErrorCode.STATUS_TRANSITION_INVALID, wo.number() + " has no report");
                }
            }
            case "CLOSED" -> reservations.releaseAll(companyId, orderId);
            default -> {
            }
        }
        setStatus(orderId, wo.status(), target, actor, reason);
        if ("CLOSED".equals(target) || "CANCELLED".equals(target)) {
            audit.record(companyId, "WORK_ORDER_" + target, "WORK_ORDER", orderId, actor, reason, wo.number());
        }
        return get(companyId, orderId);
    }

    /** Reserves what each component still needs at the order's site, all or nothing. */
    @Transactional
    public List<Reservations.Reservation> reserveMaterials(UUID companyId, UUID orderId, String actor) {
        WorkOrder wo = locked(companyId, orderId);
        if (!Set.of("PLANNED", "RELEASED", "IN_PROGRESS").contains(wo.status())) {
            throw new MystixException(ErrorCode.STATUS_TRANSITION_INVALID, wo.number() + " is " + wo.status());
        }
        List<Reservations.Reservation> made = new ArrayList<>();
        for (Material m : materials(companyId, wo)) {
            BigDecimal missing = m.remaining().subtract(m.reserved());
            if (missing.signum() > 0) {
                made.add(reservations.reserve(companyId, m.componentId(), null, wo.siteId(), missing, "WORK_ORDER",
                        wo.number(), orderId, actor));
            }
        }
        return made;
    }

    // ---------- materials ----------

    /**
     * Issues material of one lot to the order. Covered first by the order's reservations, otherwise taken from
     * available stock. Beyond the theoretical need plus the tolerance, a justification is required.
     */
    @Transactional
    public Consumption issue(UUID companyId, UUID orderId, UUID lotId, UUID locationId, BigDecimal quantity,
                             String unit, String idempotencyKey, String justification, String actor) {
        WorkOrder wo = locked(companyId, orderId);
        Consumption replay = replay(companyId, orderId, idempotencyKey, "ISSUE", lotId, quantity);
        if (replay != null) {
            return replay;
        }
        requireRunning(companyId, wo, actor);
        Lots.Lot lot = lots.get(companyId, lotId);
        Material line = materials(companyId, wo).stream().filter(m -> m.componentId().equals(lot.productId()))
                .findFirst().orElseThrow(() -> new MystixException(ErrorCode.VALIDATION_FAILED,
                        lot.sku() + " is not a component of " + wo.number()));
        WmsCatalog.Product product = catalog.product(companyId, lot.productId());
        BigDecimal base = catalog.toBase(product, quantity, unit);
        WmsCatalog.Location location = catalog.location(companyId, locationId);
        if (!location.siteId().equals(wo.siteId())) {
            throw new MystixException(ErrorCode.VALIDATION_FAILED, "Location " + location.code() + " is on another site");
        }
        BigDecimal limit = line.required().multiply(BigDecimal.ONE.add(tolerancePercent.movePointLeft(2)));
        if (line.issued().add(base).compareTo(limit) > 0 && (justification == null || justification.isBlank())) {
            throw new MystixException(ErrorCode.JUSTIFICATION_REQUIRED, lot.sku() + ": " + line.issued().add(base)
                    + " issued for a need of " + line.required());
        }
        ledger.lockProduct(companyId, product.id());
        BigDecimal own = reservations.remaining(companyId, orderId, product.id(), lot.id()).min(base);
        ledger.checkWithdrawal(companyId, lot, location, base, own);
        UUID movement = ledger.post(companyId, new Ledger.Post("PRODUCTION_ISSUE", product.id(), lot.id(), base,
                quantity, unit == null || unit.isBlank() ? product.baseUnit() : unit.strip().toUpperCase(),
                location.id(), null, "WORK_ORDER", orderId, OffsetDateTime.now(clock), actor,
                "Consommation " + wo.number()));
        reservations.consume(companyId, orderId, product.id(), lot.id(), base);
        return record(companyId, orderId, "ISSUE", lot, location, base, movement, idempotencyKey, justification, actor);
    }

    /** Returns unused material of one lot from the order to a location; never more than the lot's net issue. */
    @Transactional
    public Consumption returnMaterial(UUID companyId, UUID orderId, UUID lotId, UUID locationId, BigDecimal quantity,
                                      String unit, String idempotencyKey, String actor) {
        WorkOrder wo = locked(companyId, orderId);
        Consumption replay = replay(companyId, orderId, idempotencyKey, "RETURN", lotId, quantity);
        if (replay != null) {
            return replay;
        }
        if (!Set.of("RELEASED", "IN_PROGRESS", "ON_HOLD", "COMPLETED").contains(wo.status())) {
            throw new MystixException(ErrorCode.STATUS_TRANSITION_INVALID, wo.number() + " is " + wo.status());
        }
        Lots.Lot lot = lots.get(companyId, lotId);
        WmsCatalog.Product product = catalog.product(companyId, lot.productId());
        BigDecimal base = catalog.toBase(product, quantity, unit);
        BigDecimal net = netIssued(orderId, lotId);
        if (base.compareTo(net) > 0) {
            throw new MystixException(ErrorCode.VALIDATION_FAILED, "Only " + net + " of lot " + lot.lotNumber()
                    + " was issued to " + wo.number());
        }
        WmsCatalog.Location location = catalog.location(companyId, locationId);
        if (!location.siteId().equals(wo.siteId()) || "TRANSIT".equals(location.type())) {
            throw new MystixException(ErrorCode.VALIDATION_FAILED, "Return location must be on the order's site");
        }
        UUID movement = ledger.post(companyId, new Ledger.Post("PRODUCTION_RETURN", product.id(), lot.id(), base,
                quantity, unit == null || unit.isBlank() ? product.baseUnit() : unit.strip().toUpperCase(), null,
                location.id(), "WORK_ORDER", orderId, OffsetDateTime.now(clock), actor, "Retour " + wo.number()));
        return record(companyId, orderId, "RETURN", lot, location, base, movement, idempotencyKey, null, actor);
    }

    // ---------- production ----------

    public record NewResidue(UUID productId, BigDecimal quantity, String unit, String fate, String destination) {
    }

    public record NewReport(String reportKey, BigDecimal goodQuantity, BigDecimal scrapQuantity, String scrapCause,
                            String productionLine, String operator, String comment, OffsetDateTime reportedAt,
                            String outputLotNumber, List<NewResidue> residues) {
    }

    /**
     * Declares production. Good quantity becomes a lot of the made product, entered in stock at the order's output
     * location (PENDING or RELEASED according to the product), linked to every lot issued to the order. Recovered
     * residues become by-product lots; waste and residues awaiting qualification are recorded without stock. The same
     * report key with the same content returns the first declaration; with another content it is refused.
     */
    @Transactional
    public Report report(UUID companyId, UUID orderId, NewReport r, String actor) {
        WorkOrder wo = locked(companyId, orderId);
        String sha = Sha256.hex(fingerprint(r).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        var existing = jdbc.sql("""
                        SELECT id, payload_sha256 FROM gpao_production_report
                        WHERE work_order_id = :wo AND report_key = :k
                        """)
                .param("wo", orderId).param("k", r.reportKey())
                .query((rs, n) -> Map.entry(rs.getObject("id", UUID.class), rs.getString("payload_sha256")))
                .optional();
        if (existing.isPresent()) {
            if (!existing.get().getValue().equals(sha)) {
                throw new MystixException(ErrorCode.REPLAY_CONFLICT, "Report key " + r.reportKey() + " reused");
            }
            return reports(companyId, orderId).stream().filter(x -> x.id().equals(existing.get().getKey()))
                    .findFirst().orElseThrow();
        }
        requireRunning(companyId, wo, actor);
        BigDecimal good = nz(r.goodQuantity());
        BigDecimal scrap = nz(r.scrapQuantity());
        if (good.signum() < 0 || scrap.signum() < 0 || good.add(scrap).signum() == 0) {
            throw new MystixException(ErrorCode.VALIDATION_FAILED, "Declare a positive good or scrap quantity");
        }
        if (scrap.signum() > 0 && (r.scrapCause() == null || r.scrapCause().isBlank())) {
            throw new MystixException(ErrorCode.JUSTIFICATION_REQUIRED, "Scrap without cause");
        }
        OffsetDateTime now = OffsetDateTime.now(clock);
        OffsetDateTime at = r.reportedAt() == null ? now : r.reportedAt();
        WmsCatalog.Product made = catalog.product(companyId, wo.productId());
        List<UUID> inputs = issuedLots(orderId);
        UUID reportId = UUID.randomUUID();
        Lots.Lot output = null;
        if (good.signum() > 0) {
            String number = r.outputLotNumber() == null || r.outputLotNumber().isBlank()
                    ? wo.number() + "-" + (count("gpao_production_report", orderId) + 1) : r.outputLotNumber().strip();
            output = lots.create(companyId, made.id(), number, "PRODUCTION", made.receiptQuality(), null, null,
                    orderId, Map.of(), at);
            ledger.post(companyId, new Ledger.Post("PRODUCTION_OUTPUT", made.id(), output.id(), good, good,
                    made.baseUnit(), null, wo.outputLocationId(), "WORK_ORDER", orderId, at, actor,
                    "Production " + wo.number()));
            for (UUID input : inputs) {
                lots.link(companyId, input, output.id(), orderId, null);
            }
        }
        jdbc.sql("""
                        INSERT INTO gpao_production_report (id, company_id, work_order_id, report_key, payload_sha256,
                                                            good_quantity, scrap_quantity, scrap_cause,
                                                            production_line, operator, comment, output_lot_id,
                                                            reported_at, recorded_at, actor)
                        VALUES (:id, :c, :wo, :k, :sha, :good, :scrap, :cause, :line, :op, :comment, :lot, :at, :now,
                                :actor)
                        """)
                .param("id", reportId).param("c", companyId).param("wo", orderId).param("k", r.reportKey())
                .param("sha", sha).param("good", good).param("scrap", scrap).param("cause", r.scrapCause())
                .param("line", r.productionLine() == null ? wo.productionLine() : r.productionLine())
                .param("op", r.operator()).param("comment", r.comment())
                .param("lot", output == null ? null : output.id()).param("at", at).param("now", now)
                .param("actor", actor).update();
        if (r.residues() != null) {
            for (NewResidue res : r.residues()) {
                residue(companyId, wo, reportId, res, inputs, at, actor);
            }
        }
        jdbc.sql("""
                        UPDATE gpao_work_order
                        SET produced_quantity = produced_quantity + :good + :scrap,
                            good_quantity = good_quantity + :good, scrap_quantity = scrap_quantity + :scrap,
                            updated_at = :now
                        WHERE id = :id
                        """)
                .param("good", good).param("scrap", scrap).param("now", now).param("id", orderId).update();
        return reports(companyId, orderId).stream().filter(x -> x.id().equals(reportId)).findFirst().orElseThrow();
    }

    private void residue(UUID companyId, WorkOrder wo, UUID reportId, NewResidue res, List<UUID> inputs,
                         OffsetDateTime at, String actor) {
        if (res.quantity() == null || res.quantity().signum() <= 0) {
            throw new MystixException(ErrorCode.VALIDATION_FAILED, "Residue quantity must be positive");
        }
        String fate = res.fate() == null ? "" : res.fate();
        if (!Set.of("WASTE", "RECOVERED", "PENDING").contains(fate)) {
            throw new MystixException(ErrorCode.VALIDATION_FAILED, "Residue fate must be WASTE, RECOVERED or PENDING");
        }
        WmsCatalog.Product product = res.productId() == null ? null : catalog.product(companyId, res.productId());
        if (product == null && !"WASTE".equals(fate)) {
            throw new MystixException(ErrorCode.VALIDATION_FAILED, "A recovered or pending residue names its product");
        }
        String unit = product == null ? (res.unit() == null ? "KG" : res.unit().strip().toUpperCase())
                : (res.unit() == null || res.unit().isBlank() ? product.baseUnit() : res.unit().strip().toUpperCase());
        UUID lotId = null;
        if ("RECOVERED".equals(fate)) {
            BigDecimal base = catalog.toBase(product, res.quantity(), unit);
            Lots.Lot lot = lots.create(companyId, product.id(), wo.number() + "-SP" + (count("gpao_residue", wo.id())
                    + 1), "BY_PRODUCT", product.receiptQuality(), null, null, wo.id(), Map.of(), at);
            ledger.post(companyId, new Ledger.Post("BY_PRODUCT_OUTPUT", product.id(), lot.id(), base, res.quantity(),
                    unit, null, wo.outputLocationId(), "WORK_ORDER", wo.id(), at, actor,
                    "Sous-produit " + wo.number()));
            for (UUID input : inputs) {
                lots.link(companyId, input, lot.id(), wo.id(), null);
            }
            lotId = lot.id();
        }
        jdbc.sql("""
                        INSERT INTO gpao_residue (id, company_id, report_id, work_order_id, product_id, quantity, unit,
                                                  fate, destination, lot_id)
                        VALUES (:id, :c, :r, :wo, :p, :q, :u, :fate, :dest, :lot)
                        """)
                .param("id", UUID.randomUUID()).param("c", companyId).param("r", reportId).param("wo", wo.id())
                .param("p", product == null ? null : product.id()).param("q", res.quantity()).param("u", unit)
                .param("fate", fate).param("dest", res.destination()).param("lot", lotId).update();
    }

    // ---------- reads ----------

    public Detail detail(UUID companyId, UUID orderId) {
        WorkOrder wo = get(companyId, orderId);
        return new Detail(wo, boms.get(companyId, wo.bomId()), materials(companyId, wo),
                consumptions(companyId, orderId), reports(companyId, orderId), history(orderId),
                variance(companyId, wo));
    }

    public Variance variance(UUID companyId, WorkOrder wo) {
        Boms.Bom bom = boms.get(companyId, wo.bomId());
        BigDecimal produced = wo.producedQuantity();
        Map<UUID, BigDecimal> issued = issuedByProduct(wo.id());
        List<ComponentVariance> components = new ArrayList<>();
        for (Boms.Requirement req : boms.requirements(bom, produced)) {
            BigDecimal actual = issued.getOrDefault(req.componentId(), BigDecimal.ZERO);
            components.add(new ComponentVariance(req.componentId(), req.sku(), req.unit(), req.quantity(), actual,
                    ratio(actual.subtract(req.quantity()), req.quantity())));
        }
        return new Variance(wo.plannedQuantity(), produced, wo.goodQuantity(), wo.scrapQuantity(),
                ratio(produced, wo.plannedQuantity()), ratio(wo.scrapQuantity(), produced), components);
    }

    public List<Material> materials(UUID companyId, WorkOrder wo) {
        Boms.Bom bom = boms.get(companyId, wo.bomId());
        Map<UUID, BigDecimal> issued = issuedByProduct(wo.id());
        List<Material> list = new ArrayList<>();
        for (Boms.Requirement req : boms.requirements(bom, wo.plannedQuantity())) {
            BigDecimal reserved = reservations.forReference(companyId, wo.id()).stream()
                    .filter(r -> "ACTIVE".equals(r.status()) && r.productId().equals(req.componentId()))
                    .map(Reservations.Reservation::remaining).reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal net = issued.getOrDefault(req.componentId(), BigDecimal.ZERO);
            BigDecimal remaining = req.quantity().subtract(net).max(BigDecimal.ZERO);
            BigDecimal available = ledger.availability(companyId, req.componentId(), null, wo.siteId()).available();
            list.add(new Material(req.componentId(), req.sku(), req.name(), req.unit(), req.quantity(), reserved, net,
                    remaining, available));
        }
        return list;
    }

    public WorkOrder get(UUID companyId, UUID orderId) {
        return list(companyId, null, orderId, 1).stream().findFirst()
                .orElseThrow(() -> new MystixException(ErrorCode.ATELYS_NOT_FOUND, "Work order " + orderId));
    }

    public List<WorkOrder> list(UUID companyId, String status, UUID orderId, int limit) {
        return jdbc.sql("""
                        SELECT w.*, p.sku, p.name, p.base_unit, b.version, loc.code AS output_code
                        FROM gpao_work_order w
                        JOIN wms_product p ON p.id = w.product_id
                        JOIN gpao_bom b ON b.id = w.bom_id
                        JOIN wms_location loc ON loc.id = w.output_location_id
                        WHERE w.company_id = :c AND (CAST(:s AS varchar) IS NULL OR w.status = :s)
                          AND (CAST(:id AS uuid) IS NULL OR w.id = :id)
                        ORDER BY CASE w.status WHEN 'IN_PROGRESS' THEN 0 WHEN 'RELEASED' THEN 1 WHEN 'ON_HOLD' THEN 2
                                 WHEN 'PLANNED' THEN 3 WHEN 'DRAFT' THEN 4 WHEN 'COMPLETED' THEN 5 ELSE 6 END,
                                 w.priority, w.planned_start NULLS LAST, w.number
                        LIMIT :limit
                        """)
                .param("c", companyId).param("s", status).param("id", orderId).param("limit", limit)
                .query((rs, n) -> new WorkOrder(rs.getObject("id", UUID.class), rs.getString("number"),
                        rs.getObject("product_id", UUID.class), rs.getString("sku"), rs.getString("name"),
                        rs.getString("base_unit"), rs.getObject("bom_id", UUID.class), rs.getInt("version"),
                        rs.getBigDecimal("planned_quantity"), rs.getObject("site_id", UUID.class),
                        rs.getString("production_line"), rs.getObject("output_location_id", UUID.class),
                        rs.getString("output_code"), rs.getObject("planned_start", LocalDate.class),
                        rs.getObject("planned_end", LocalDate.class), rs.getInt("priority"), rs.getString("status"),
                        rs.getString("customer_reference"), rs.getBigDecimal("produced_quantity"),
                        rs.getBigDecimal("good_quantity"), rs.getBigDecimal("scrap_quantity"),
                        rs.getString("responsible"), rs.getObject("created_at", OffsetDateTime.class),
                        rs.getObject("updated_at", OffsetDateTime.class)))
                .list();
    }

    public List<Consumption> consumptions(UUID companyId, UUID orderId) {
        return jdbc.sql("""
                        SELECT c.*, p.sku, p.base_unit, l.lot_number, loc.code AS location_code
                        FROM gpao_consumption c
                        JOIN wms_product p ON p.id = c.product_id
                        JOIN wms_lot l ON l.id = c.lot_id
                        JOIN wms_location loc ON loc.id = c.location_id
                        WHERE c.company_id = :c AND c.work_order_id = :wo ORDER BY c.at
                        """)
                .param("c", companyId).param("wo", orderId)
                .query((rs, n) -> new Consumption(rs.getObject("id", UUID.class), rs.getString("kind"),
                        rs.getObject("product_id", UUID.class), rs.getString("sku"),
                        rs.getObject("lot_id", UUID.class), rs.getString("lot_number"),
                        rs.getObject("location_id", UUID.class), rs.getString("location_code"),
                        rs.getBigDecimal("quantity"), rs.getString("base_unit"), rs.getString("justification"),
                        rs.getString("actor"), rs.getObject("at", OffsetDateTime.class)))
                .list();
    }

    public List<Report> reports(UUID companyId, UUID orderId) {
        Map<UUID, List<Residue>> residues = new LinkedHashMap<>();
        jdbc.sql("""
                        SELECT r.*, p.sku, l.lot_number FROM gpao_residue r
                        LEFT JOIN wms_product p ON p.id = r.product_id
                        LEFT JOIN wms_lot l ON l.id = r.lot_id
                        WHERE r.company_id = :c AND r.work_order_id = :wo
                        """)
                .param("c", companyId).param("wo", orderId)
                .query(rs -> {
                    residues.computeIfAbsent(rs.getObject("report_id", UUID.class), k -> new ArrayList<>())
                            .add(new Residue(rs.getObject("id", UUID.class), rs.getObject("product_id", UUID.class),
                                    rs.getString("sku"), rs.getBigDecimal("quantity"), rs.getString("unit"),
                                    rs.getString("fate"), rs.getString("destination"),
                                    rs.getObject("lot_id", UUID.class), rs.getString("lot_number")));
                });
        return jdbc.sql("""
                        SELECT r.*, l.lot_number FROM gpao_production_report r
                        LEFT JOIN wms_lot l ON l.id = r.output_lot_id
                        WHERE r.company_id = :c AND r.work_order_id = :wo ORDER BY r.reported_at, r.recorded_at
                        """)
                .param("c", companyId).param("wo", orderId)
                .query((rs, n) -> new Report(rs.getObject("id", UUID.class), rs.getString("report_key"),
                        rs.getBigDecimal("good_quantity"), rs.getBigDecimal("scrap_quantity"),
                        rs.getString("scrap_cause"), rs.getString("production_line"), rs.getString("operator"),
                        rs.getString("comment"), rs.getObject("output_lot_id", UUID.class),
                        rs.getString("lot_number"), rs.getObject("reported_at", OffsetDateTime.class),
                        rs.getString("actor"),
                        List.copyOf(residues.getOrDefault(rs.getObject("id", UUID.class), List.of()))))
                .list();
    }

    // ---------- helpers ----------

    private void requireRunning(UUID companyId, WorkOrder wo, String actor) {
        if ("RELEASED".equals(wo.status())) {
            setStatus(wo.id(), "RELEASED", "IN_PROGRESS", actor, "Premier mouvement de production");
        } else if (!"IN_PROGRESS".equals(wo.status())) {
            throw new MystixException(ErrorCode.STATUS_TRANSITION_INVALID, wo.number() + " is " + wo.status());
        }
    }

    private Consumption record(UUID companyId, UUID orderId, String kind, Lots.Lot lot, WmsCatalog.Location location,
                               BigDecimal base, UUID movement, String key, String justification, String actor) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                        INSERT INTO gpao_consumption (id, company_id, work_order_id, product_id, lot_id, location_id,
                                                      kind, quantity, movement_id, idempotency_key, justification,
                                                      actor, at)
                        VALUES (:id, :c, :wo, :p, :lot, :loc, :kind, :q, :m, :key, :just, :actor, :now)
                        """)
                .param("id", id).param("c", companyId).param("wo", orderId).param("p", lot.productId())
                .param("lot", lot.id()).param("loc", location.id()).param("kind", kind).param("q", base)
                .param("m", movement).param("key", key == null || key.isBlank() ? null : key.strip())
                .param("just", justification).param("actor", actor).param("now", OffsetDateTime.now(clock)).update();
        return consumptions(companyId, orderId).stream().filter(c -> c.id().equals(id)).findFirst().orElseThrow();
    }

    private Consumption replay(UUID companyId, UUID orderId, String key, String kind, UUID lotId,
                               BigDecimal quantity) {
        if (key == null || key.isBlank()) {
            return null;
        }
        var row = jdbc.sql("""
                        SELECT id, kind, lot_id FROM gpao_consumption WHERE work_order_id = :wo AND idempotency_key = :k
                        """)
                .param("wo", orderId).param("k", key.strip())
                .query((rs, n) -> new Object[] {rs.getObject("id", UUID.class), rs.getString("kind"),
                        rs.getObject("lot_id", UUID.class)})
                .optional();
        if (row.isEmpty()) {
            return null;
        }
        if (!kind.equals(row.get()[1]) || !lotId.equals(row.get()[2])) {
            throw new MystixException(ErrorCode.REPLAY_CONFLICT, "Key " + key + " already used");
        }
        return consumptions(companyId, orderId).stream().filter(c -> c.id().equals(row.get()[0])).findFirst()
                .orElseThrow();
    }

    private Map<UUID, BigDecimal> issuedByProduct(UUID orderId) {
        Map<UUID, BigDecimal> map = new LinkedHashMap<>();
        jdbc.sql("""
                        SELECT product_id, SUM(CASE kind WHEN 'ISSUE' THEN quantity ELSE -quantity END) AS net
                        FROM gpao_consumption WHERE work_order_id = :wo GROUP BY product_id
                        """)
                .param("wo", orderId)
                .query(rs -> {
                    map.put(rs.getObject("product_id", UUID.class), rs.getBigDecimal("net"));
                });
        return map;
    }

    private BigDecimal netIssued(UUID orderId, UUID lotId) {
        return jdbc.sql("""
                        SELECT COALESCE(SUM(CASE kind WHEN 'ISSUE' THEN quantity ELSE -quantity END), 0)
                        FROM gpao_consumption WHERE work_order_id = :wo AND lot_id = :lot
                        """)
                .param("wo", orderId).param("lot", lotId).query(BigDecimal.class).single();
    }

    /** Lots with a positive net issue to the order: the inputs of what it produces. */
    private List<UUID> issuedLots(UUID orderId) {
        return jdbc.sql("""
                        SELECT lot_id FROM gpao_consumption WHERE work_order_id = :wo GROUP BY lot_id
                        HAVING SUM(CASE kind WHEN 'ISSUE' THEN quantity ELSE -quantity END) > 0
                        """)
                .param("wo", orderId).query(UUID.class).list();
    }

    private WorkOrder locked(UUID companyId, UUID orderId) {
        jdbc.sql("SELECT id FROM gpao_work_order WHERE company_id = :c AND id = :id FOR UPDATE")
                .param("c", companyId).param("id", orderId).query(UUID.class).optional()
                .orElseThrow(() -> new MystixException(ErrorCode.ATELYS_NOT_FOUND, "Work order " + orderId));
        return get(companyId, orderId);
    }

    private void setStatus(UUID orderId, String from, String to, String actor, String reason) {
        OffsetDateTime now = OffsetDateTime.now(clock);
        jdbc.sql("UPDATE gpao_work_order SET status = :s, updated_at = :now WHERE id = :id")
                .param("s", to).param("now", now).param("id", orderId).update();
        history(orderId, from, to, actor, reason, now);
    }

    private void history(UUID orderId, String from, String to, String actor, String reason, OffsetDateTime at) {
        jdbc.sql("""
                        INSERT INTO gpao_work_order_history (id, work_order_id, from_status, to_status, actor, reason, at)
                        VALUES (:id, :wo, :from, :to, :actor, :reason, :at)
                        """)
                .param("id", UUID.randomUUID()).param("wo", orderId).param("from", from).param("to", to)
                .param("actor", actor).param("reason", reason).param("at", at).update();
    }

    private List<History> history(UUID orderId) {
        return jdbc.sql("SELECT * FROM gpao_work_order_history WHERE work_order_id = :wo ORDER BY at, to_status")
                .param("wo", orderId)
                .query((rs, n) -> new History(rs.getString("from_status"), rs.getString("to_status"),
                        rs.getString("actor"), rs.getString("reason"), rs.getObject("at", OffsetDateTime.class)))
                .list();
    }

    private int count(String table, UUID orderId) {
        return jdbc.sql("SELECT COUNT(*) FROM " + table + " WHERE work_order_id = :wo").param("wo", orderId)
                .query(Integer.class).single();
    }

    private static String fingerprint(NewReport r) {
        StringBuilder s = new StringBuilder();
        s.append(nz(r.goodQuantity()).stripTrailingZeros().toPlainString()).append('|')
                .append(nz(r.scrapQuantity()).stripTrailingZeros().toPlainString()).append('|')
                .append(r.scrapCause()).append('|').append(r.outputLotNumber());
        if (r.residues() != null) {
            for (NewResidue res : r.residues()) {
                s.append('|').append(res.productId()).append(':')
                        .append(res.quantity() == null ? null : res.quantity().stripTrailingZeros().toPlainString())
                        .append(':').append(res.unit()).append(':').append(res.fate());
            }
        }
        return s.toString();
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    /** a / b with 4 decimals, null when b is zero. */
    static BigDecimal ratio(BigDecimal a, BigDecimal b) {
        return b == null || b.signum() == 0 ? null : a.divide(b, 4, RoundingMode.HALF_UP);
    }
}
