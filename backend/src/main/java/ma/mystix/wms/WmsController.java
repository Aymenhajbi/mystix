package ma.mystix.wms;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import ma.mystix.auth.Access;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Atelys WMS API (ADR-0014), for the environment named by {@code X-Mystix-Company-Id} (checked against the session
 * by the auth filter). Sensitive actions (lot quality, inventory approval) need an environment administrator.
 */
@RestController
@RequestMapping(value = "/api/v1/wms", produces = MediaType.APPLICATION_JSON_VALUE)
class WmsController {

    private static final String COMPANY = "X-Mystix-Company-Id";
    private static final int MAX = 500;

    private final WmsCatalog catalog;
    private final Lots lots;
    private final Ledger ledger;
    private final Reservations reservations;
    private final Receipts receipts;
    private final Transfers transfers;
    private final Inventories inventories;
    private final Shipments shipments;
    private final StockViews views;
    private final AtelysAudit audit;
    private final Access access;

    WmsController(WmsCatalog catalog, Lots lots, Ledger ledger, Reservations reservations, Receipts receipts,
                  Transfers transfers, Inventories inventories, Shipments shipments, StockViews views,
                  AtelysAudit audit, Access access) {
        this.catalog = catalog;
        this.lots = lots;
        this.ledger = ledger;
        this.reservations = reservations;
        this.receipts = receipts;
        this.transfers = transfers;
        this.inventories = inventories;
        this.shipments = shipments;
        this.views = views;
        this.audit = audit;
        this.access = access;
    }

    // ---------- referentials ----------

    record UnitRequest(@NotBlank @Size(max = 16) String code, @NotBlank @Size(max = 80) String name,
                       @NotBlank @Pattern(regexp = "^(COUNT|MASS|VOLUME|LENGTH|AREA)$") String dimension) {
    }

    @GetMapping("/units")
    List<WmsCatalog.Unit> units(@RequestHeader(COMPANY) UUID companyId) {
        return catalog.units(companyId);
    }

    @PostMapping(value = "/units", consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<WmsCatalog.Unit> createUnit(@RequestHeader(COMPANY) UUID companyId,
                                               @Valid @RequestBody UnitRequest r) {
        return ResponseEntity.status(201).body(catalog.createUnit(companyId, r.code(), r.name(), r.dimension()));
    }

    record ProductRequest(@NotBlank @Size(max = 64) String sku, @NotBlank @Size(max = 200) String name,
                          @Size(max = 1000) String description,
                          @NotBlank @Pattern(regexp = "^(RAW_MATERIAL|COMPONENT|CONSUMABLE|BY_PRODUCT|SEMI_FINISHED|FINISHED)$")
                          String category,
                          @NotBlank @Size(max = 16) String baseUnit, Boolean lotTracked,
                          @Pattern(regexp = "^(PENDING|RELEASED)$") String receiptQuality,
                          @DecimalMin("0") BigDecimal alertThreshold) {
    }

    @GetMapping("/products")
    List<WmsCatalog.Product> products(@RequestHeader(COMPANY) UUID companyId,
                                      @RequestParam(required = false) String category,
                                      @RequestParam(required = false) String q) {
        return catalog.products(companyId, category, q);
    }

    @GetMapping("/products/{id}")
    WmsCatalog.Product product(@RequestHeader(COMPANY) UUID companyId, @PathVariable UUID id) {
        return catalog.product(companyId, id);
    }

    @PostMapping(value = "/products", consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<WmsCatalog.Product> createProduct(@RequestHeader(COMPANY) UUID companyId,
                                                     @Valid @RequestBody ProductRequest r) {
        return ResponseEntity.status(201).body(catalog.createProduct(companyId, new WmsCatalog.NewProduct(r.sku(),
                r.name(), r.description(), r.category(), r.baseUnit(), Boolean.TRUE.equals(r.lotTracked()),
                r.receiptQuality() == null ? "RELEASED" : r.receiptQuality(), r.alertThreshold())));
    }

    record ConversionRequest(@NotNull @DecimalMin(value = "0", inclusive = false) BigDecimal factor,
                             @Size(max = 200) String note) {
    }

    @PutMapping(value = "/products/{id}/conversions/{unit}", consumes = MediaType.APPLICATION_JSON_VALUE)
    WmsCatalog.Product setConversion(@RequestHeader(COMPANY) UUID companyId, @PathVariable UUID id,
                                     @PathVariable String unit, @Valid @RequestBody ConversionRequest r) {
        return catalog.setConversion(companyId, id, unit, r.factor(), r.note());
    }

    record SiteRequest(@NotBlank @Size(max = 32) String code, @NotBlank @Size(max = 120) String name) {
    }

    @GetMapping("/sites")
    List<WmsCatalog.Site> sites(@RequestHeader(COMPANY) UUID companyId) {
        return catalog.sites(companyId);
    }

    @PostMapping(value = "/sites", consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<WmsCatalog.Site> createSite(@RequestHeader(COMPANY) UUID companyId,
                                               @Valid @RequestBody SiteRequest r) {
        return ResponseEntity.status(201).body(catalog.createSite(companyId, r.code(), r.name()));
    }

    @GetMapping("/warehouses")
    List<WmsCatalog.Warehouse> warehouses(@RequestHeader(COMPANY) UUID companyId,
                                          @RequestParam(required = false) UUID siteId) {
        return catalog.warehouses(companyId, siteId);
    }

    @PostMapping(value = "/sites/{siteId}/warehouses", consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<WmsCatalog.Warehouse> createWarehouse(@RequestHeader(COMPANY) UUID companyId,
                                                         @PathVariable UUID siteId,
                                                         @Valid @RequestBody SiteRequest r) {
        return ResponseEntity.status(201).body(catalog.createWarehouse(companyId, siteId, r.code(), r.name()));
    }

    record LocationRequest(@NotBlank @Size(max = 40) String code, @Size(max = 40) String zone,
                           @NotBlank @Pattern(regexp = "^(RECEIVING|STORAGE|PRODUCTION|QUARANTINE|SHIPPING)$")
                           String type) {
    }

    @GetMapping("/locations")
    List<WmsCatalog.Location> locations(@RequestHeader(COMPANY) UUID companyId,
                                        @RequestParam(required = false) UUID siteId,
                                        @RequestParam(required = false) UUID warehouseId) {
        return catalog.locations(companyId, siteId, warehouseId);
    }

    @PostMapping(value = "/warehouses/{warehouseId}/locations", consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<WmsCatalog.Location> createLocation(@RequestHeader(COMPANY) UUID companyId,
                                                       @PathVariable UUID warehouseId,
                                                       @Valid @RequestBody LocationRequest r) {
        return ResponseEntity.status(201).body(catalog.createLocation(companyId, warehouseId, r.code(), r.zone(),
                r.type()));
    }

    // ---------- stock ----------

    @GetMapping("/stock")
    List<StockViews.StockLine> stock(@RequestHeader(COMPANY) UUID companyId,
                                     @RequestParam(required = false) UUID siteId,
                                     @RequestParam(required = false) UUID warehouseId,
                                     @RequestParam(required = false) UUID locationId,
                                     @RequestParam(required = false) UUID productId,
                                     @RequestParam(required = false) UUID lotId,
                                     @RequestParam(required = false) String quality,
                                     @RequestParam(required = false) String q,
                                     @RequestParam(defaultValue = "200") int limit,
                                     @RequestParam(defaultValue = "0") int offset) {
        return views.lines(companyId, new StockViews.Filter(siteId, warehouseId, locationId, productId, lotId,
                quality, q), Math.clamp(limit, 1, MAX), Math.max(0, offset));
    }

    @GetMapping("/stock/products")
    List<StockViews.ProductStock> productStock(@RequestHeader(COMPANY) UUID companyId,
                                               @RequestParam(required = false) UUID siteId,
                                               @RequestParam(required = false) String q) {
        return views.products(companyId, siteId, q);
    }

    @GetMapping("/movements")
    List<Ledger.Movement> movements(@RequestHeader(COMPANY) UUID companyId,
                                    @RequestParam(required = false) UUID productId,
                                    @RequestParam(required = false) UUID lotId,
                                    @RequestParam(required = false) UUID locationId,
                                    @RequestParam(required = false) UUID referenceId,
                                    @RequestParam(defaultValue = "100") int limit) {
        return ledger.movements(companyId, productId, lotId, locationId, referenceId, Math.clamp(limit, 1, MAX));
    }

    // ---------- lots ----------

    @GetMapping("/lots")
    List<Lots.Lot> lots(@RequestHeader(COMPANY) UUID companyId, @RequestParam(required = false) String q,
                        @RequestParam(required = false) UUID productId,
                        @RequestParam(defaultValue = "100") int limit) {
        return lots.search(companyId, q, productId, Math.clamp(limit, 1, MAX));
    }

    /** A scanned QR code (public code) or a typed lot number. */
    @GetMapping("/lots/scan")
    List<Lots.Lot> scan(@RequestHeader(COMPANY) UUID companyId, @RequestParam String code) {
        return lots.resolve(companyId, code);
    }

    record LotView(Lots.Lot lot, List<StockViews.StockLine> stock, Ledger.Availability availability,
                   List<Ledger.Movement> movements, StockViews.Genealogy genealogy,
                   List<AtelysAudit.Entry> audit) {
    }

    @GetMapping("/lots/{id}")
    LotView lot(@RequestHeader(COMPANY) UUID companyId, @PathVariable UUID id) {
        Lots.Lot lot = lots.get(companyId, id);
        return new LotView(lot, views.lines(companyId, new StockViews.Filter(null, null, null, null, id, null, null),
                MAX, 0), ledger.availability(companyId, lot.productId(), id, null),
                ledger.movements(companyId, null, id, null, null, MAX), views.genealogy(companyId, id),
                audit.entries(companyId, id, 50));
    }

    record QualityRequest(@NotBlank @Pattern(regexp = "^(RELEASED|BLOCKED|REJECTED)$") String status,
                          @Size(max = 500) String reason) {
    }

    @PostMapping(value = "/lots/{id}/quality", consumes = MediaType.APPLICATION_JSON_VALUE)
    Lots.Lot quality(@RequestHeader(COMPANY) UUID companyId, @PathVariable UUID id,
                     @Valid @RequestBody QualityRequest r, HttpServletRequest http) {
        access.requireManager(http, companyId);
        return lots.setQuality(companyId, id, r.status(), r.reason(), access.actor(http));
    }

    @GetMapping("/lots/{id}/traceability")
    StockViews.Genealogy traceability(@RequestHeader(COMPANY) UUID companyId, @PathVariable UUID id) {
        lots.get(companyId, id);
        return views.genealogy(companyId, id);
    }

    // ---------- reservations ----------

    record ReservationRequest(@NotNull UUID productId, UUID lotId, @NotNull UUID siteId,
                              @NotNull @DecimalMin(value = "0", inclusive = false) BigDecimal quantity,
                              @Size(max = 100) @NotBlank String reference) {
    }

    @PostMapping(value = "/reservations", consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<Reservations.Reservation> reserve(@RequestHeader(COMPANY) UUID companyId,
                                                     @Valid @RequestBody ReservationRequest r,
                                                     HttpServletRequest http) {
        return ResponseEntity.status(201).body(reservations.reserve(companyId, r.productId(), r.lotId(), r.siteId(),
                r.quantity(), "CUSTOMER_ORDER", r.reference(), null, access.actor(http)));
    }

    @PostMapping("/reservations/{id}/release")
    Reservations.Reservation release(@RequestHeader(COMPANY) UUID companyId, @PathVariable UUID id) {
        return reservations.release(companyId, id);
    }

    @GetMapping("/reservations")
    List<Reservations.Reservation> activeReservations(@RequestHeader(COMPANY) UUID companyId,
                                                      @RequestParam UUID productId) {
        return reservations.active(companyId, productId);
    }

    // ---------- receipts ----------

    record ReceiptLineRequest(@NotNull UUID productId,
                              @NotNull @DecimalMin(value = "0", inclusive = false) BigDecimal quantity,
                              @Size(max = 16) String unit, @NotNull UUID locationId, @Size(max = 64) String lotNumber,
                              @Size(max = 64) String supplierLot, Map<String, Object> attributes) {
    }

    record ReceiptRequest(@Size(max = 40) String number, @NotNull UUID siteId, @Size(max = 200) String supplier,
                          @Size(max = 100) String sourceDocument, @NotEmpty List<@Valid ReceiptLineRequest> lines) {
    }

    @GetMapping("/receipts")
    List<Receipts.Receipt> receipts(@RequestHeader(COMPANY) UUID companyId,
                                    @RequestParam(defaultValue = "100") int limit) {
        return receipts.list(companyId, null, Math.clamp(limit, 1, MAX));
    }

    @GetMapping("/receipts/{id}")
    Receipts.Receipt receipt(@RequestHeader(COMPANY) UUID companyId, @PathVariable UUID id) {
        return receipts.get(companyId, id);
    }

    @PostMapping(value = "/receipts", consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<Receipts.Receipt> createReceipt(@RequestHeader(COMPANY) UUID companyId,
                                                   @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
                                                   @Valid @RequestBody ReceiptRequest r, HttpServletRequest http) {
        // 201 when created, 200 when the same Idempotency-Key and content had already created it.
        Receipts.Created created = receipts.create(companyId, new Receipts.NewReceipt(r.number(),
                r.siteId(), r.supplier(), r.sourceDocument(), r.lines().stream()
                .map(l -> new Receipts.NewLine(l.productId(), l.quantity(), l.unit(), l.locationId(), l.lotNumber(),
                        l.supplierLot(), l.attributes()))
                .toList()), idempotencyKey, access.actor(http));
        return ResponseEntity.status(created.replayed() ? 200 : 201).body(created.receipt());
    }

    @PostMapping("/receipts/{id}/confirm")
    Receipts.Receipt confirmReceipt(@RequestHeader(COMPANY) UUID companyId, @PathVariable UUID id,
                                    HttpServletRequest http) {
        return receipts.confirm(companyId, id, access.actor(http));
    }

    // ---------- transfers ----------

    record TransferRequest(@NotNull UUID lotId, @NotNull @DecimalMin(value = "0", inclusive = false) BigDecimal quantity,
                           @Size(max = 16) String unit, @NotNull UUID fromLocationId, @NotNull UUID toLocationId) {
    }

    @GetMapping("/transfers")
    List<Transfers.Transfer> transfers(@RequestHeader(COMPANY) UUID companyId,
                                       @RequestParam(defaultValue = "100") int limit) {
        return transfers.list(companyId, null, Math.clamp(limit, 1, MAX));
    }

    @PostMapping(value = "/transfers", consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<Transfers.Transfer> createTransfer(@RequestHeader(COMPANY) UUID companyId,
                                                      @Valid @RequestBody TransferRequest r, HttpServletRequest http) {
        return ResponseEntity.status(201).body(transfers.create(companyId, r.lotId(), r.quantity(), r.unit(),
                r.fromLocationId(), r.toLocationId(), access.actor(http)));
    }

    @PostMapping("/transfers/{id}/ship")
    Transfers.Transfer ship(@RequestHeader(COMPANY) UUID companyId, @PathVariable UUID id, HttpServletRequest http) {
        return transfers.ship(companyId, id, access.actor(http));
    }

    @PostMapping("/transfers/{id}/receive")
    Transfers.Transfer receiveTransfer(@RequestHeader(COMPANY) UUID companyId, @PathVariable UUID id,
                                       HttpServletRequest http) {
        return transfers.receive(companyId, id, access.actor(http));
    }

    // ---------- inventories ----------

    record InventoryRequest(@NotNull UUID warehouseId) {
    }

    record CountEntryRequest(@NotNull UUID lotId, @NotNull UUID locationId,
                             @NotNull @DecimalMin("0") BigDecimal counted, @Size(max = 500) String reason) {
    }

    @GetMapping("/inventories")
    List<Inventories.Count> inventories(@RequestHeader(COMPANY) UUID companyId,
                                        @RequestParam(defaultValue = "50") int limit) {
        return inventories.list(companyId, null, Math.clamp(limit, 1, MAX));
    }

    @GetMapping("/inventories/{id}")
    Inventories.Count inventory(@RequestHeader(COMPANY) UUID companyId, @PathVariable UUID id) {
        return inventories.get(companyId, id);
    }

    @PostMapping(value = "/inventories", consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<Inventories.Count> openInventory(@RequestHeader(COMPANY) UUID companyId,
                                                    @Valid @RequestBody InventoryRequest r, HttpServletRequest http) {
        return ResponseEntity.status(201).body(inventories.open(companyId, r.warehouseId(), access.actor(http)));
    }

    @PutMapping(value = "/inventories/{id}/counts", consumes = MediaType.APPLICATION_JSON_VALUE)
    Inventories.Count count(@RequestHeader(COMPANY) UUID companyId, @PathVariable UUID id,
                            @RequestBody List<@Valid CountEntryRequest> entries) {
        return inventories.count(companyId, id, entries.stream()
                .map(e -> new Inventories.Entry(e.lotId(), e.locationId(), e.counted(), e.reason())).toList());
    }

    @PostMapping("/inventories/{id}/submit")
    Inventories.Count submitInventory(@RequestHeader(COMPANY) UUID companyId, @PathVariable UUID id,
                                      HttpServletRequest http) {
        return inventories.submit(companyId, id, access.actor(http));
    }

    @PostMapping("/inventories/{id}/approve")
    Inventories.Count approveInventory(@RequestHeader(COMPANY) UUID companyId, @PathVariable UUID id,
                                       HttpServletRequest http) {
        access.requireManager(http, companyId);
        return inventories.approve(companyId, id, access.actor(http));
    }

    // ---------- shipments ----------

    record ShipmentLineRequest(@NotNull UUID lotId, @NotNull UUID locationId,
                               @NotNull @DecimalMin(value = "0", inclusive = false) BigDecimal quantity,
                               @Size(max = 16) String unit) {
    }

    record ShipmentRequest(@NotNull UUID siteId, @Size(max = 200) String customer,
                           @Size(max = 100) String orderReference,
                           @NotEmpty List<@Valid ShipmentLineRequest> lines) {
    }

    @GetMapping("/shipments")
    List<Shipments.Shipment> shipments(@RequestHeader(COMPANY) UUID companyId,
                                       @RequestParam(defaultValue = "100") int limit) {
        return shipments.list(companyId, null, Math.clamp(limit, 1, MAX));
    }

    @GetMapping("/shipments/{id}")
    Shipments.Shipment shipment(@RequestHeader(COMPANY) UUID companyId, @PathVariable UUID id) {
        return shipments.get(companyId, id);
    }

    @PostMapping(value = "/shipments", consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<Shipments.Shipment> createShipment(@RequestHeader(COMPANY) UUID companyId,
                                                      @Valid @RequestBody ShipmentRequest r, HttpServletRequest http) {
        return ResponseEntity.status(201).body(shipments.create(companyId, r.siteId(), r.customer(),
                r.orderReference(), r.lines().stream().map(l -> new Shipments.NewLine(l.lotId(), l.locationId(),
                        l.quantity(), l.unit())).toList(), access.actor(http)));
    }

    /** Body: line number → picked quantity; lines left out are picked in full. */
    @PostMapping("/shipments/{id}/confirm")
    Shipments.Shipment confirmShipment(@RequestHeader(COMPANY) UUID companyId, @PathVariable UUID id,
                                       @RequestBody(required = false) Map<Integer, BigDecimal> picked,
                                       HttpServletRequest http) {
        return shipments.confirm(companyId, id, picked, access.actor(http));
    }

    // ---------- audit ----------

    @GetMapping("/audit")
    List<AtelysAudit.Entry> audit(@RequestHeader(COMPANY) UUID companyId,
                                  @RequestParam(required = false) UUID objectId,
                                  @RequestParam(defaultValue = "100") int limit) {
        return audit.entries(companyId, objectId, Math.clamp(limit, 1, MAX));
    }
}
