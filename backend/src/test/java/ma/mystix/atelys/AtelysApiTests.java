package ma.mystix.atelys;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import ma.mystix.TestcontainersConfiguration;
import ma.mystix.tenant.CompanyService;
import ma.mystix.tenant.Ice;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClient;

/**
 * Atelys GPAO + WMS end to end (ADR-0014), on fictitious data: the reference scenario of the specification, then
 * the invariants one by one (no double count on retry, no stock created by a transfer, blocked lots stay put,
 * reservations never exceed what is available, no negative stock under concurrency, tenant isolation).
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "mystix.atelys.demo-enabled=true")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@SuppressWarnings({"unchecked", "rawtypes"})
class AtelysApiTests {

    @Value("${local.server.port}")
    int port;

    @Autowired
    CompanyService companies;

    RestClient http;
    UUID company;
    UUID site;
    UUID depot;
    UUID stock;
    UUID stock2;
    UUID quarantine;
    UUID output;
    UUID depotStock;
    UUID wood;
    UUID block;
    UUID nails;
    UUID pallet;
    UUID sawdust;
    UUID warehouse;

    @BeforeAll
    void referentials() {
        http = RestClient.builder().baseUrl("http://localhost:" + port)
                .defaultStatusHandler(status -> true, (request, response) -> { }).build();
        company = companies.register(new Ice("000000040000040"), "Scierie Fictive Test SARL", null).id();
        post("/api/v1/wms/units", Map.of("code", "U", "name", "Unité", "dimension", "COUNT"));
        post("/api/v1/wms/units", Map.of("code", "KG", "name", "Kilogramme", "dimension", "MASS"));
        post("/api/v1/wms/units", Map.of("code", "BOTTE", "name", "Botte", "dimension", "COUNT"));
        site = id(post("/api/v1/wms/sites", Map.of("code", "S1", "name", "Site test")));
        depot = id(post("/api/v1/wms/sites", Map.of("code", "S2", "name", "Dépôt test")));
        warehouse = id(post("/api/v1/wms/sites/" + site + "/warehouses", Map.of("code", "W1", "name", "Matières")));
        UUID w2 = id(post("/api/v1/wms/sites/" + depot + "/warehouses", Map.of("code", "W2", "name", "Dépôt")));
        stock = location(warehouse, "A1", "STORAGE");
        stock2 = location(warehouse, "A2", "STORAGE");
        quarantine = location(warehouse, "Q1", "QUARANTINE");
        output = location(warehouse, "PF1", "STORAGE");
        depotStock = location(w2, "D1", "STORAGE");
        wood = product("T-PLANCHE", "RAW_MATERIAL", "U", true, "PENDING");
        block = product("T-DE", "COMPONENT", "U", true, "RELEASED");
        nails = product("T-CLOU", "CONSUMABLE", "KG", false, "RELEASED");
        pallet = product("T-PALETTE", "FINISHED", "U", true, "PENDING");
        sawdust = product("T-SCIURE", "BY_PRODUCT", "KG", true, "RELEASED");
        // One bundle of boards is worth 25 boards: an explicit conversion, nothing implicit.
        put("/api/v1/wms/products/" + wood + "/conversions/BOTTE", Map.of("factor", 25));
    }

    @Test
    void referenceScenarioFromReceiptToFinishedLotAndGenealogy() {
        // 1. Finished product and its BOM: 9 boards (3 % loss), 9 blocks, 0.15 kg of nails per pallet.
        UUID bom = id(post("/api/v1/gpao/boms", Map.of("productId", pallet, "referenceQuantity", 1, "lines", List.of(
                Map.of("componentId", wood, "quantity", 9, "lossPercent", 3),
                Map.of("componentId", block, "quantity", 9),
                Map.of("componentId", nails, "quantity", 0.15, "lossPercent", 5)))));
        // 2. Materials received: 40 bundles of boards = 1000 boards, entered as PENDING (product policy).
        Map receipt = post("/api/v1/wms/receipts", Map.of("siteId", site, "supplier", "Fournisseur fictif",
                "lines", List.of(
                        Map.of("productId", wood, "quantity", 40, "unit", "BOTTE", "locationId", stock,
                                "lotNumber", "T-PLA-1", "attributes", Map.of("humidite_pct", 17)),
                        Map.of("productId", block, "quantity", 1000, "locationId", stock, "lotNumber", "T-DE-1"),
                        Map.of("productId", nails, "quantity", 20, "locationId", stock)))).getBody();
        Map confirmed = post("/api/v1/wms/receipts/" + receipt.get("id") + "/confirm", null).getBody();
        assertThat(confirmed).containsEntry("status", "CONFIRMED");
        UUID woodLot = UUID.fromString((String) lines(confirmed).get(0).get("lotId"));
        UUID blockLot = UUID.fromString((String) lines(confirmed).get(1).get("lotId"));
        UUID nailLot = UUID.fromString((String) lines(confirmed).get(2).get("lotId"));
        assertThat(productStock(wood)).containsEntry("physical", 1000.0).containsEntry("blocked", 1000.0)
                .containsEntry("available", 0.0);

        // 3. Work order cannot be released on a draft BOM.
        UUID wo = id(post("/api/v1/gpao/work-orders", Map.of("productId", pallet, "bomId", bom, "plannedQuantity",
                100, "siteId", site, "outputLocationId", output)));
        assertThat(post("/api/v1/gpao/work-orders/" + wo + "/status", Map.of("status", "RELEASED")).getBody())
                .containsEntry("errorCode", "BOM_NOT_VALIDATED");
        post("/api/v1/gpao/boms/" + bom + "/validate", null);
        assertThat(post("/api/v1/gpao/work-orders/" + wo + "/status", Map.of("status", "RELEASED")).getBody())
                .containsEntry("status", "RELEASED");

        // 4. Boards still PENDING: not reservable, not consumable. Release them, then reserve.
        assertThat(post("/api/v1/gpao/work-orders/" + wo + "/reserve-materials", null).getBody())
                .containsEntry("errorCode", "STOCK_NOT_AVAILABLE");
        assertThat(post("/api/v1/gpao/work-orders/" + wo + "/material-consumptions", Map.of("lotId", woodLot,
                "locationId", stock, "quantity", 10)).getBody()).containsEntry("errorCode", "LOT_NOT_RELEASED");
        post("/api/v1/wms/lots/" + woodLot + "/quality", Map.of("status", "RELEASED", "reason", "Humidité conforme"));
        List<Map> reserved = getList("/api/v1/gpao/work-orders/" + wo + "/reserve-materials", true);
        assertThat(reserved).hasSize(3);
        // Theoretical need: 9 x 100 x 1.03 = 927 boards; 900 blocks; 0.15 x 100 x 1.05 = 15.75 kg.
        assertThat(productStock(wood)).containsEntry("reserved", 927.0).containsEntry("available", 73.0);

        // 5. Issue materials, idempotently; a retried issue does not issue twice.
        Map issue = Map.of("lotId", woodLot, "locationId", stock, "quantity", 470, "idempotencyKey", "i-1");
        post("/api/v1/gpao/work-orders/" + wo + "/material-consumptions", issue);
        post("/api/v1/gpao/work-orders/" + wo + "/material-consumptions", issue);
        post("/api/v1/gpao/work-orders/" + wo + "/material-consumptions", Map.of("lotId", blockLot, "locationId",
                stock, "quantity", 450));
        post("/api/v1/gpao/work-orders/" + wo + "/material-consumptions", Map.of("lotId", nailLot, "locationId",
                stock, "quantity", 7.5));
        assertThat(productStock(wood)).containsEntry("physical", 530.0).containsEntry("reserved", 457.0);

        // 6. Partial declaration: 48 good, 2 scrap, sawdust recovered (35 kg) and waste (4 kg).
        Map report = Map.of("reportKey", "r-1", "goodQuantity", 48, "scrapQuantity", 2, "scrapCause", "Fente",
                "residues", List.of(Map.of("productId", sawdust, "quantity", 35, "fate", "RECOVERED",
                        "destination", "GRANULES"), Map.of("quantity", 4, "unit", "KG", "fate", "WASTE")));
        Map first = post("/api/v1/gpao/work-orders/" + wo + "/production-reports", report).getBody();
        // A retried declaration counts once; the same key with another content is refused.
        assertThat(post("/api/v1/gpao/work-orders/" + wo + "/production-reports", report).getBody())
                .containsEntry("id", first.get("id"));
        assertThat(post("/api/v1/gpao/work-orders/" + wo + "/production-reports", Map.of("reportKey", "r-1",
                "goodQuantity", 49)).getBody()).containsEntry("errorCode", "REPLAY_CONFLICT");

        // 7. Final declaration, then completion.
        post("/api/v1/gpao/work-orders/" + wo + "/production-reports", Map.of("reportKey", "r-2", "goodQuantity", 2));
        Map detail = get("/api/v1/gpao/work-orders/" + wo);
        Map order = (Map) detail.get("order");
        assertThat(order).containsEntry("status", "IN_PROGRESS").containsEntry("producedQuantity", 52.0)
                .containsEntry("goodQuantity", 50.0).containsEntry("scrapQuantity", 2.0);
        Map variance = (Map) detail.get("variance");
        assertThat(variance).containsEntry("completion", 0.52).containsEntry("scrapRate", 0.0385);

        // 8-9. Finished lots: in stock at the output location, PENDING quality (product policy), 50 in total.
        assertThat(productStock(pallet)).containsEntry("physical", 50.0).containsEntry("blocked", 50.0);
        // Sawdust: only what was recovered is stock; the waste is not.
        assertThat(productStock(sawdust)).containsEntry("physical", 35.0);

        // 10. Genealogy both ways.
        UUID palletLot = UUID.fromString((String) first.get("outputLotId"));
        Map trace = get("/api/v1/wms/lots/" + palletLot + "/traceability");
        assertThat((List<Map>) trace.get("upstream")).extracting(m -> m.get("lotNumber"))
                .contains("T-PLA-1", "T-DE-1", "SANS-LOT");
        Map down = get("/api/v1/wms/lots/" + woodLot + "/traceability");
        assertThat((List<Map>) down.get("downstream")).extracting(m -> m.get("sku"))
                .contains("T-PALETTE", "T-SCIURE");

        // 11. Completion, closing: the reservations left are released, the residual stock adds up.
        post("/api/v1/gpao/work-orders/" + wo + "/status", Map.of("status", "COMPLETED"));
        assertThat(post("/api/v1/gpao/work-orders/" + wo + "/status", Map.of("status", "CLOSED")).getBody())
                .containsEntry("status", "CLOSED");
        assertThat(productStock(wood)).containsEntry("reserved", 0.0).containsEntry("available", 530.0);
        assertThat(productStock(block)).containsEntry("physical", 550.0);
        assertThat(productStock(nails)).containsEntry("physical", 12.5);
    }

    @Test
    void receiptsTransfersAndInventoryKeepQuantitiesExact() {
        UUID item = product("T-DE-TR", "COMPONENT", "U", true, "RELEASED");
        UUID lot = receive(item, 100, stock, "T-DE-TR");
        // Confirming twice records the receipt once.
        assertThat(productStockAt(item, "T-DE-TR")).isEqualTo(100.0);

        // Within a site: moved at once, nothing created or destroyed.
        UUID move = id(post("/api/v1/wms/transfers", Map.of("lotId", lot, "quantity", 30, "fromLocationId", stock,
                "toLocationId", stock2)));
        assertThat(post("/api/v1/wms/transfers/" + move + "/ship", null).getBody()).containsEntry("status", "RECEIVED");
        assertThat(lotTotal(lot)).isEqualTo(100.0);

        // Between sites: in transit, then received.
        UUID trip = id(post("/api/v1/wms/transfers", Map.of("lotId", lot, "quantity", 20, "fromLocationId", stock2,
                "toLocationId", depotStock)));
        assertThat(post("/api/v1/wms/transfers/" + trip + "/ship", null).getBody()).containsEntry("status", "SHIPPED");
        Map lotView = get("/api/v1/wms/lots/" + lot);
        assertThat((Map) lotView.get("availability")).containsEntry("inTransit", 20.0).containsEntry("physical", 80.0);
        post("/api/v1/wms/transfers/" + trip + "/receive", null);
        post("/api/v1/wms/transfers/" + trip + "/receive", null);
        assertThat((Map) get("/api/v1/wms/lots/" + lot).get("availability")).containsEntry("inTransit", 0.0)
                .containsEntry("physical", 100.0);

        // Inventory of W1: counted 68 where 70 are expected at A1; the gap needs a reason, then an approval.
        UUID count = id(post("/api/v1/wms/inventories", Map.of("warehouseId", warehouse)));
        Map opened = get("/api/v1/wms/inventories/" + count);
        List<Map> countLines = (List<Map>) opened.get("lines");
        List<Map<String, Object>> entries = new ArrayList<>();
        for (Map l : countLines) {
            boolean target = lot.toString().equals(l.get("lotId")) && stock.toString().equals(l.get("locationId"));
            entries.add(Map.of("lotId", l.get("lotId"), "locationId", l.get("locationId"),
                    "counted", target ? 68 : l.get("expected")));
        }
        put("/api/v1/wms/inventories/" + count + "/counts", entries);
        assertThat(post("/api/v1/wms/inventories/" + count + "/submit", null).getBody())
                .containsEntry("errorCode", "JUSTIFICATION_REQUIRED");
        put("/api/v1/wms/inventories/" + count + "/counts", List.of(Map.of("lotId", lot, "locationId", stock,
                "counted", 68, "reason", "Casse constatée")));
        post("/api/v1/wms/inventories/" + count + "/submit", null);
        assertThat(post("/api/v1/wms/inventories/" + count + "/approve", null).getBody())
                .containsEntry("status", "APPROVED");
        post("/api/v1/wms/inventories/" + count + "/approve", null);
        assertThat(lotTotal(lot)).isEqualTo(98.0);
    }

    @Test
    void blockedStockAndReservationsAreRespected() {
        UUID item = product("T-DE-BL", "COMPONENT", "U", true, "RELEASED");
        UUID lot = receive(item, 50, stock, "T-DE-BLK");
        UUID free = receive(item, 10, stock, "T-DE-FREE");
        // Blocking needs a reason; a blocked lot can neither be shipped nor issued.
        assertThat(post("/api/v1/wms/lots/" + lot + "/quality", Map.of("status", "BLOCKED")).getBody())
                .containsEntry("errorCode", "JUSTIFICATION_REQUIRED");
        post("/api/v1/wms/lots/" + lot + "/quality", Map.of("status", "BLOCKED", "reason", "Contrôle visuel"));
        UUID ship = id(post("/api/v1/wms/shipments", Map.of("siteId", site, "customer", "Client fictif",
                "lines", List.of(Map.of("lotId", lot, "locationId", stock, "quantity", 5)))));
        assertThat(post("/api/v1/wms/shipments/" + ship + "/confirm", null).getBody())
                .containsEntry("errorCode", "LOT_NOT_RELEASED");
        assertThat(lotTotal(lot)).isEqualTo(50.0);

        // A reservation beyond what is available is refused; within it, it takes stock out of available.
        double available = (Double) productStock(item).get("available");
        assertThat(post("/api/v1/wms/reservations", Map.of("productId", item, "siteId", site,
                "quantity", available + 1, "reference", "CMD-X")).getBody())
                .containsEntry("errorCode", "STOCK_NOT_AVAILABLE");
        UUID reservation = id(post("/api/v1/wms/reservations", Map.of("productId", item, "siteId", site,
                "quantity", available, "reference", "CMD-Y")));
        assertThat(productStock(item)).containsEntry("available", 0.0);
        UUID other = id(post("/api/v1/wms/shipments", Map.of("siteId", site,
                "lines", List.of(Map.of("lotId", free, "locationId", stock, "quantity", 1)))));
        assertThat(post("/api/v1/wms/shipments/" + other + "/confirm", null).getBody())
                .containsEntry("errorCode", "STOCK_NOT_AVAILABLE");
        post("/api/v1/wms/reservations/" + reservation + "/release", null);
        assertThat(post("/api/v1/wms/shipments/" + other + "/confirm", null).getBody())
                .containsEntry("status", "SHIPPED");
        post("/api/v1/wms/lots/" + lot + "/quality", Map.of("status", "RELEASED"));
    }

    @Test
    void concurrentWithdrawalsNeverDriveStockNegative() throws Exception {
        UUID item = product("T-CLOU-C", "CONSUMABLE", "KG", false, "RELEASED");
        UUID lot = receive(item, 10, stock2, null);
        // Ten shipments of 2 kg each race for 10 kg released at A2 .
        List<UUID> shipments = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            shipments.add(id(post("/api/v1/wms/shipments", Map.of("siteId", site,
                    "lines", List.of(Map.of("lotId", lot, "locationId", stock2, "quantity", 2))))));
        }
        double before = balanceAt(lot, stock2);
        ExecutorService pool = Executors.newFixedThreadPool(10);
        List<Future<Integer>> results = new ArrayList<>();
        for (UUID s : shipments) {
            results.add(pool.submit((Callable<Integer>) () ->
                    post("/api/v1/wms/shipments/" + s + "/confirm", null).getStatusCode().value()));
        }
        int ok = 0;
        for (Future<Integer> f : results) {
            ok += f.get() == 200 ? 1 : 0;
        }
        pool.shutdown();
        double after = balanceAt(lot, stock2);
        assertThat(after).isGreaterThanOrEqualTo(0.0);
        assertThat(before - after).isEqualTo(ok * 2.0);
        assertThat(ok).isLessThanOrEqualTo((int) (before / 2));
    }

    @Test
    void bomsUnitsAndRatiosAreChecked() {
        // A BOM using its own product is refused.
        assertThat(post("/api/v1/gpao/boms", Map.of("productId", pallet, "referenceQuantity", 1,
                "lines", List.of(Map.of("componentId", pallet, "quantity", 1)))).getBody())
                .containsEntry("errorCode", "BOM_INVALID");
        // An unknown unit is refused, never guessed.
        assertThat(post("/api/v1/wms/receipts", Map.of("siteId", site, "lines", List.of(Map.of("productId", block,
                "quantity", 1, "unit", "BOTTE", "locationId", stock)))).getBody())
                .containsEntry("errorCode", "UNIT_INCOMPATIBLE");
        // A fresh order: ratios are null, not a division by zero.
        UUID board = product("T-PLA-OVR", "RAW_MATERIAL", "U", true, "PENDING");
        UUID bom = id(post("/api/v1/gpao/boms", Map.of("productId", sawdust, "referenceQuantity", 10,
                "lines", List.of(Map.of("componentId", board, "quantity", 1)))));
        post("/api/v1/gpao/boms/" + bom + "/validate", null);
        UUID wo = id(post("/api/v1/gpao/work-orders", Map.of("productId", sawdust, "plannedQuantity", 10,
                "siteId", site, "outputLocationId", output)));
        Map variance = get("/api/v1/gpao/work-orders/" + wo + "/variance");
        assertThat(variance).containsEntry("scrapRate", null).containsEntry("completion", 0.0);
        // Over-issue beyond the need plus 10 % needs a justification.
        post("/api/v1/gpao/work-orders/" + wo + "/status", Map.of("status", "RELEASED"));
        UUID lot = receive(board, 10, stock, "T-PLA-OVR");
        post("/api/v1/wms/lots/" + lot + "/quality", Map.of("status", "RELEASED"));
        assertThat(post("/api/v1/gpao/work-orders/" + wo + "/material-consumptions", Map.of("lotId", lot,
                "locationId", stock, "quantity", 2)).getBody()).containsEntry("errorCode", "JUSTIFICATION_REQUIRED");
        assertThat(post("/api/v1/gpao/work-orders/" + wo + "/material-consumptions", Map.of("lotId", lot,
                "locationId", stock, "quantity", 2, "justification", "Essai")).getStatusCode())
                .isEqualTo(HttpStatus.CREATED);
        // An order with issues cannot be cancelled.
        assertThat(post("/api/v1/gpao/work-orders/" + wo + "/status", Map.of("status", "CANCELLED")).getBody())
                .containsEntry("errorCode", "STATUS_TRANSITION_INVALID");
    }

    @Test
    void anotherEnvironmentSeesNothingAndDemoDataIsIdempotent() {
        UUID item = product("T-DE-ISO", "COMPONENT", "U", true, "RELEASED");
        UUID lot = receive(item, 1, stock, "T-DE-ISO");
        UUID stranger = companies.register(new Ice("000000041000041"), "Autre Industriel Fictif SARL", null).id();
        ResponseEntity<Map> foreign = http.get().uri("/api/v1/wms/lots/" + lot)
                .header("X-Mystix-Company-Id", stranger.toString()).retrieve().toEntity(Map.class);
        assertThat(foreign.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(foreign.getBody()).containsEntry("errorCode", "ATELYS_NOT_FOUND");

        Map seeded = http.post().uri("/api/v1/atelys/demo").header("X-Mystix-Company-Id", stranger.toString())
                .retrieve().body(Map.class);
        assertThat(seeded).containsEntry("created", true);
        Map again = http.post().uri("/api/v1/atelys/demo").header("X-Mystix-Company-Id", stranger.toString())
                .retrieve().body(Map.class);
        assertThat(again).containsEntry("created", false).containsEntry("workOrderId", seeded.get("workOrderId"));
        Map dashboard = http.get().uri("/api/v1/gpao/dashboard").header("X-Mystix-Company-Id", stranger.toString())
                .retrieve().body(Map.class);
        assertThat((Map) dashboard.get("workOrdersByStatus")).containsEntry("IN_PROGRESS", 1);
        assertThat((Integer) dashboard.get("lotsPendingQuality")).isGreaterThanOrEqualTo(2);
    }

    // ---------- helpers ----------

    private UUID receive(UUID product, double quantity, UUID location, String lotNumber) {
        Map<String, Object> line = new java.util.HashMap<>(Map.of("productId", product, "quantity", quantity,
                "locationId", location));
        if (lotNumber != null) {
            line.put("lotNumber", lotNumber);
        }
        Map receipt = post("/api/v1/wms/receipts", Map.of("siteId", site, "lines", List.of(line))).getBody();
        post("/api/v1/wms/receipts/" + receipt.get("id") + "/confirm", null);
        Map confirmed = post("/api/v1/wms/receipts/" + receipt.get("id") + "/confirm", null).getBody();
        return UUID.fromString((String) lines(confirmed).get(0).get("lotId"));
    }

    private UUID location(UUID warehouseId, String code, String type) {
        return id(post("/api/v1/wms/warehouses/" + warehouseId + "/locations", Map.of("code", code, "type", type)));
    }

    private UUID product(String sku, String category, String unit, boolean lotTracked, String quality) {
        return id(post("/api/v1/wms/products", Map.of("sku", sku, "name", sku + " (test)", "category", category,
                "baseUnit", unit, "lotTracked", lotTracked, "receiptQuality", quality)));
    }

    private Map productStock(UUID product) {
        List<Map> list = getList("/api/v1/wms/stock/products?siteId=" + site, false);
        Map row = list.stream().filter(m -> product.toString().equals(m.get("productId"))).findFirst().orElseThrow();
        return Map.of("physical", num(row.get("physical")), "blocked", num(row.get("blocked")),
                "reserved", num(row.get("reserved")), "available", num(row.get("available")));
    }

    private double productStockAt(UUID product, String lotNumber) {
        return getList("/api/v1/wms/stock?productId=" + product + "&q=" + lotNumber, false).stream()
                .mapToDouble(m -> num(m.get("quantity"))).sum();
    }

    private double lotTotal(UUID lot) {
        return getList("/api/v1/wms/stock?lotId=" + lot, false).stream().mapToDouble(m -> num(m.get("quantity")))
                .sum();
    }

    private double balanceAt(UUID lot, UUID location) {
        return getList("/api/v1/wms/stock?lotId=" + lot + "&locationId=" + location, false).stream()
                .mapToDouble(m -> num(m.get("quantity"))).sum();
    }

    private static double num(Object o) {
        return o == null ? 0 : new BigDecimal(o.toString()).doubleValue();
    }

    private static List<Map> lines(Map receipt) {
        return (List<Map>) receipt.get("lines");
    }

    private static UUID id(ResponseEntity<Map> response) {
        assertThat(response.getStatusCode().is2xxSuccessful()).as(String.valueOf(response.getBody())).isTrue();
        return UUID.fromString((String) response.getBody().get("id"));
    }

    private ResponseEntity<Map> post(String uri, Object body) {
        var request = http.post().uri(uri).header("X-Mystix-Company-Id", company.toString());
        if (body != null) {
            request = request.contentType(MediaType.APPLICATION_JSON).body(body);
        }
        return request.retrieve().toEntity(Map.class);
    }

    private void put(String uri, Object body) {
        ResponseEntity<Map> r = http.put().uri(uri).header("X-Mystix-Company-Id", company.toString())
                .contentType(MediaType.APPLICATION_JSON).body(body).retrieve().toEntity(Map.class);
        assertThat(r.getStatusCode().is2xxSuccessful()).as(String.valueOf(r.getBody())).isTrue();
    }

    private Map get(String uri) {
        return http.get().uri(uri).header("X-Mystix-Company-Id", company.toString()).retrieve().body(Map.class);
    }

    private List<Map> getList(String uri, boolean viaPost) {
        if (viaPost) {
            return http.post().uri(uri).header("X-Mystix-Company-Id", company.toString()).retrieve()
                    .body(List.class);
        }
        return http.get().uri(uri).header("X-Mystix-Company-Id", company.toString()).retrieve().body(List.class);
    }
}
