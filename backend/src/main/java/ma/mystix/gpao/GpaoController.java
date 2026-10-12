package ma.mystix.gpao;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import ma.mystix.auth.Access;
import ma.mystix.wms.Reservations;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Atelys GPAO API (ADR-0014): bills of materials, work orders, issues, production reports, dashboard. Validating a
 * BOM and closing a work order need an environment administrator.
 */
@RestController
@RequestMapping(value = "/api/v1/gpao", produces = MediaType.APPLICATION_JSON_VALUE)
class GpaoController {

    private static final String COMPANY = "X-Mystix-Company-Id";

    private final Boms boms;
    private final WorkOrders workOrders;
    private final ProductionDashboard dashboard;
    private final Access access;

    GpaoController(Boms boms, WorkOrders workOrders, ProductionDashboard dashboard, Access access) {
        this.boms = boms;
        this.workOrders = workOrders;
        this.dashboard = dashboard;
        this.access = access;
    }

    // ---------- bills of materials ----------

    record BomLineRequest(@NotNull UUID componentId,
                          @NotNull @DecimalMin(value = "0", inclusive = false) BigDecimal quantity,
                          @DecimalMin("0") BigDecimal lossPercent, Boolean substitutionAllowed) {
    }

    record OperationRequest(@Min(1) int sequence, @NotBlank @Size(max = 120) String name,
                            @Size(max = 80) String workstation, @DecimalMin("0") BigDecimal standardMinutes,
                            @Size(max = 1000) String instructions, @Size(max = 500) String qualityCheck) {
    }

    record BomRequest(@NotNull UUID productId,
                      @NotNull @DecimalMin(value = "0", inclusive = false) BigDecimal referenceQuantity,
                      LocalDate validFrom, LocalDate validTo, @Size(max = 500) String note,
                      @NotEmpty List<@Valid BomLineRequest> lines, List<@Valid OperationRequest> operations) {
    }

    @GetMapping("/boms")
    List<Boms.Bom> boms(@RequestHeader(COMPANY) UUID companyId, @RequestParam(required = false) UUID productId) {
        return boms.list(companyId, productId, null);
    }

    @GetMapping("/boms/{id}")
    Boms.Bom bom(@RequestHeader(COMPANY) UUID companyId, @PathVariable UUID id) {
        return boms.get(companyId, id);
    }

    @PostMapping(value = "/boms", consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<Boms.Bom> createBom(@RequestHeader(COMPANY) UUID companyId, @Valid @RequestBody BomRequest r,
                                       HttpServletRequest http) {
        return ResponseEntity.status(201).body(boms.create(companyId, new Boms.NewBom(r.productId(),
                r.referenceQuantity(), r.validFrom(), r.validTo(), r.note(), r.lines().stream()
                .map(l -> new Boms.NewLine(l.componentId(), l.quantity(), l.lossPercent(),
                        Boolean.TRUE.equals(l.substitutionAllowed())))
                .toList(), r.operations() == null ? List.of() : r.operations().stream()
                .map(o -> new Boms.Operation(o.sequence(), o.name(), o.workstation(), o.standardMinutes(),
                        o.instructions(), o.qualityCheck())).toList()), access.actor(http)));
    }

    @PostMapping("/boms/{id}/validate")
    Boms.Bom validateBom(@RequestHeader(COMPANY) UUID companyId, @PathVariable UUID id, HttpServletRequest http) {
        access.requireManager(http, companyId);
        return boms.validate(companyId, id, access.actor(http));
    }

    @PostMapping("/boms/{id}/obsolete")
    Boms.Bom obsoleteBom(@RequestHeader(COMPANY) UUID companyId, @PathVariable UUID id, HttpServletRequest http) {
        access.requireManager(http, companyId);
        return boms.obsolete(companyId, id, access.actor(http));
    }

    // ---------- work orders ----------

    record WorkOrderRequest(@Size(max = 40) String number, @NotNull UUID productId, UUID bomId,
                            @NotNull @DecimalMin(value = "0", inclusive = false) BigDecimal plannedQuantity,
                            @NotNull UUID siteId, @Size(max = 80) String productionLine,
                            @NotNull UUID outputLocationId, LocalDate plannedStart, LocalDate plannedEnd,
                            @Min(1) @Max(5) Integer priority, @Size(max = 100) String customerReference,
                            @Size(max = 254) String responsible) {
    }

    @GetMapping("/work-orders")
    List<WorkOrders.WorkOrder> workOrders(@RequestHeader(COMPANY) UUID companyId,
                                          @RequestParam(required = false) String status,
                                          @RequestParam(defaultValue = "200") int limit) {
        return workOrders.list(companyId, status, null, Math.clamp(limit, 1, 500));
    }

    @GetMapping("/work-orders/{id}")
    WorkOrders.Detail workOrder(@RequestHeader(COMPANY) UUID companyId, @PathVariable UUID id) {
        return workOrders.detail(companyId, id);
    }

    @PostMapping(value = "/work-orders", consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<WorkOrders.WorkOrder> createWorkOrder(@RequestHeader(COMPANY) UUID companyId,
                                                         @Valid @RequestBody WorkOrderRequest r,
                                                         HttpServletRequest http) {
        return ResponseEntity.status(201).body(workOrders.create(companyId, new WorkOrders.NewWorkOrder(r.number(),
                r.productId(), r.bomId(), r.plannedQuantity(), r.siteId(), r.productionLine(), r.outputLocationId(),
                r.plannedStart(), r.plannedEnd(), r.priority(), r.customerReference(), r.responsible()),
                access.actor(http)));
    }

    record TransitionRequest(@NotBlank @Pattern(regexp =
            "^(PLANNED|RELEASED|IN_PROGRESS|ON_HOLD|COMPLETED|CLOSED|CANCELLED|DRAFT)$") String status,
                             @Size(max = 500) String reason) {
    }

    @PostMapping(value = "/work-orders/{id}/status", consumes = MediaType.APPLICATION_JSON_VALUE)
    WorkOrders.WorkOrder transition(@RequestHeader(COMPANY) UUID companyId, @PathVariable UUID id,
                                    @Valid @RequestBody TransitionRequest r, HttpServletRequest http) {
        if ("CLOSED".equals(r.status())) {
            access.requireManager(http, companyId);
        }
        return workOrders.transition(companyId, id, r.status(), r.reason(), access.actor(http));
    }

    @PostMapping("/work-orders/{id}/reserve-materials")
    List<Reservations.Reservation> reserve(@RequestHeader(COMPANY) UUID companyId, @PathVariable UUID id,
                                           HttpServletRequest http) {
        return workOrders.reserveMaterials(companyId, id, access.actor(http));
    }

    record MaterialRequest(@NotNull UUID lotId, @NotNull UUID locationId,
                           @NotNull @DecimalMin(value = "0", inclusive = false) BigDecimal quantity,
                           @Size(max = 16) String unit, @Size(max = 100) String idempotencyKey,
                           @Size(max = 500) String justification) {
    }

    @PostMapping(value = "/work-orders/{id}/material-consumptions", consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<WorkOrders.Consumption> issue(@RequestHeader(COMPANY) UUID companyId, @PathVariable UUID id,
                                                 @Valid @RequestBody MaterialRequest r, HttpServletRequest http) {
        return ResponseEntity.status(201).body(workOrders.issue(companyId, id, r.lotId(), r.locationId(),
                r.quantity(), r.unit(), r.idempotencyKey(), r.justification(), access.actor(http)));
    }

    @PostMapping(value = "/work-orders/{id}/material-returns", consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<WorkOrders.Consumption> returnMaterial(@RequestHeader(COMPANY) UUID companyId,
                                                          @PathVariable UUID id,
                                                          @Valid @RequestBody MaterialRequest r,
                                                          HttpServletRequest http) {
        return ResponseEntity.status(201).body(workOrders.returnMaterial(companyId, id, r.lotId(), r.locationId(),
                r.quantity(), r.unit(), r.idempotencyKey(), access.actor(http)));
    }

    record ResidueRequest(UUID productId, @NotNull @DecimalMin(value = "0", inclusive = false) BigDecimal quantity,
                          @Size(max = 16) String unit, @NotBlank @Pattern(regexp = "^(WASTE|RECOVERED|PENDING)$")
                          String fate, @Size(max = 40) String destination) {
    }

    record ReportRequest(@NotBlank @Size(max = 100) String reportKey, @DecimalMin("0") BigDecimal goodQuantity,
                         @DecimalMin("0") BigDecimal scrapQuantity, @Size(max = 200) String scrapCause,
                         @Size(max = 80) String productionLine, @Size(max = 120) String operator,
                         @Size(max = 500) String comment, OffsetDateTime reportedAt,
                         @Size(max = 64) String outputLotNumber, List<@Valid ResidueRequest> residues) {
    }

    @PostMapping(value = "/work-orders/{id}/production-reports", consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<WorkOrders.Report> report(@RequestHeader(COMPANY) UUID companyId, @PathVariable UUID id,
                                             @Valid @RequestBody ReportRequest r, HttpServletRequest http) {
        return ResponseEntity.status(201).body(workOrders.report(companyId, id, new WorkOrders.NewReport(
                r.reportKey(), r.goodQuantity(), r.scrapQuantity(), r.scrapCause(), r.productionLine(), r.operator(),
                r.comment(), r.reportedAt(), r.outputLotNumber(), r.residues() == null ? List.of() : r.residues()
                .stream().map(x -> new WorkOrders.NewResidue(x.productId(), x.quantity(), x.unit(), x.fate(),
                        x.destination())).toList()), access.actor(http)));
    }

    @GetMapping("/work-orders/{id}/variance")
    WorkOrders.Variance variance(@RequestHeader(COMPANY) UUID companyId, @PathVariable UUID id) {
        return workOrders.variance(companyId, workOrders.get(companyId, id));
    }

    @GetMapping("/dashboard")
    ProductionDashboard.Dashboard dashboard(@RequestHeader(COMPANY) UUID companyId,
                                            @RequestParam(required = false) UUID siteId) {
        return dashboard.dashboard(companyId, siteId);
    }
}
