package ma.mystix.stock;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import ma.mystix.TestcontainersConfiguration;
import ma.mystix.tenant.CompanyService;
import ma.mystix.tenant.Ice;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
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

/** Stock module end to end (ADR-0011): events, positions, receipt disputes, snapshot reconciliation, isolation. */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class StockApiTests {

    private static final String SITE = "CASA/MAIN";

    @Value("${local.server.port}")
    int port;

    @Autowired
    CompanyService companies;

    RestClient http;
    UUID company;
    UUID other;

    @BeforeAll
    void registerCompanies() {
        company = companies.register(new Ice("000000020000020"), "Entrepot Synthetique SARL", null).id();
        other = companies.register(new Ice("000000021000021"), "Autre Entrepot SARL", null).id();
    }

    @BeforeEach
    void setUp() {
        http = RestClient.builder().baseUrl("http://localhost:" + port)
                .defaultStatusHandler(status -> true, (request, response) -> { }).build();
    }

    @Test
    void supplyAndDistributionMoveStockBetweenItsStates() {
        // DESADV: 100 in transit; available to promise counts it, on-hand does not.
        assertThat(post(company, event("SHIPMENT_NOTICE_IN", "desadv-1", "DESADV-1", null, "2026-09-15T08:00:00Z",
                line("SKU-A", Map.of("quantity", 100)))).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertPosition("SKU-A", Map.of("IN_TRANSIT", 100.0), 0.0, 100.0);

        // RECADV against DESADV-1: 90 accepted, 6 refused (quarantine), 4 missing; supplier dispute alert.
        ResponseEntity<Map> receipt = post(company, event("RECEIPT", "recadv-1", "RECADV-1", "DESADV-1",
                "2026-09-15T09:00:00Z", line("SKU-A", Map.of("accepted", 90, "refused", 6, "missing", 4))));
        assertThat(receipt.getBody()).containsEntry("alerts", 1).containsEntry("movements", 3);
        assertPosition("SKU-A", Map.of("AVAILABLE", 90.0, "QUARANTINE", 6.0, "IN_TRANSIT", 0.0), 96.0, 90.0);
        assertThat(alerts(company)).anySatisfy(a -> assertThat(a)
                .containsEntry("kind", "RECEIPT_DISCREPANCY").containsEntry("sku", "SKU-A"));

        // ORDERS: 30 reserved, taken out of available to promise.
        assertThat(post(company, event("ORDER", "orders-1", "ORD-1", null, "2026-09-15T10:00:00Z",
                line("SKU-A", Map.of("quantity", 30)))).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertPosition("SKU-A", Map.of("AVAILABLE", 60.0, "RESERVED", 30.0), 96.0, 60.0);

        // An order beyond what is available is refused and leaves no trace.
        ResponseEntity<Map> tooMuch = post(company, event("ORDER", "orders-2", "ORD-2", null, "2026-09-15T10:05:00Z",
                line("SKU-A", Map.of("quantity", 100))));
        assertThat(tooMuch.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(tooMuch.getBody()).containsEntry("errorCode", "STOCK_INSUFFICIENT");
        assertPosition("SKU-A", Map.of("AVAILABLE", 60.0, "RESERVED", 30.0), 96.0, 60.0);

        // Same event again: replayed, nothing applied twice; same key with other content: refused.
        ResponseEntity<Map> replay = post(company, event("ORDER", "orders-1", "ORD-1", null, "2026-09-15T10:00:00Z",
                line("SKU-A", Map.of("quantity", 30))));
        assertThat(replay.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(replay.getBody()).containsEntry("replayed", true).containsEntry("movements", 1);
        assertThat(post(company, event("ORDER", "orders-1", "ORD-1", null, "2026-09-15T10:00:00Z",
                line("SKU-A", Map.of("quantity", 31)))).getBody()).containsEntry("errorCode", "STOCK_EVENT_CONFLICT");
        assertPosition("SKU-A", Map.of("AVAILABLE", 60.0, "RESERVED", 30.0), 96.0, 60.0);

        // DESADV outbound: reserved stock leaves; quality hold; breakage.
        post(company, event("SHIPMENT_OUT", "desadv-out-1", "DESADV-OUT-1", null, "2026-09-15T11:00:00Z",
                line("SKU-A", Map.of("quantity", 30))));
        post(company, event("STATUS_CHANGE", "invrpt-sts-1", "INVRPT-1", null, "2026-09-15T12:00:00Z",
                line("SKU-A", Map.of("quantity", 5, "fromState", "AVAILABLE", "toState", "QUARANTINE"))));
        post(company, event("ADJUSTMENT", "invrpt-adj-1", "INVRPT-2", null, "2026-09-15T12:30:00Z",
                line("SKU-A", Map.of("quantity", -2, "state", "AVAILABLE"))));
        assertPosition("SKU-A", Map.of("AVAILABLE", 53.0, "RESERVED", 0.0, "QUARANTINE", 11.0), 64.0, 53.0);
        assertThat(list(company, "/api/v1/stock/movements?sku=SKU-A")).hasSize(8);
    }

    @Test
    void snapshotKeepsEventsIntegratedAfterItsInstantAndRecordsTheVariance() {
        post(company, event("SHIPMENT_NOTICE_IN", "snap-desadv", "DESADV-S", null, "2026-09-16T08:00:00Z",
                line("SKU-S", Map.of("quantity", 50))));
        post(company, event("RECEIPT", "snap-recadv", "RECADV-S", "DESADV-S", "2026-09-16T09:00:00Z",
                line("SKU-S", Map.of("accepted", 50))));
        // An order dated 10:30 is integrated before the 10:00 snapshot arrives.
        post(company, event("ORDER", "snap-order", "ORD-S", null, "2026-09-16T10:30:00Z",
                line("SKU-S", Map.of("quantity", 10))));
        assertPosition("SKU-S", Map.of("AVAILABLE", 40.0, "RESERVED", 10.0), 50.0, 40.0);

        // At 10:00 the warehouse counted 48 available (2 lost) and nothing in quarantine.
        ResponseEntity<Map> snapshot = post(company, event("SNAPSHOT", "snap-1", "INVRPT-S", null, "2026-09-16T10:00:00Z",
                line("SKU-S", Map.of("state", "AVAILABLE", "quantity", 48)),
                line("SKU-S", Map.of("state", "QUARANTINE", "quantity", 0))));
        assertThat(snapshot.getBody()).containsEntry("variances", 1).containsEntry("linesCompared", 2)
                .containsEntry("linesMatched", 1);
        // Warehouse value applied as of 10:00, the 10:30 order kept: 48 - 10 available, 10 reserved.
        assertPosition("SKU-S", Map.of("AVAILABLE", 38.0, "RESERVED", 10.0), 48.0, 38.0);
        assertThat(alerts(company)).anySatisfy(a -> assertThat(a)
                .containsEntry("kind", "INVENTORY_VARIANCE").containsEntry("sku", "SKU-S"));

        // The same snapshot again changes nothing.
        ResponseEntity<Map> again = post(company, event("SNAPSHOT", "snap-1", "INVRPT-S", null, "2026-09-16T10:00:00Z",
                line("SKU-S", Map.of("state", "AVAILABLE", "quantity", 48)),
                line("SKU-S", Map.of("state", "QUARANTINE", "quantity", 0))));
        assertThat(again.getBody()).containsEntry("replayed", true).containsEntry("linesMatched", 1);
        assertPosition("SKU-S", Map.of("AVAILABLE", 38.0, "RESERVED", 10.0), 48.0, 38.0);

        // Read side: accuracy history and the item's alerts only.
        assertThat(list(company, "/api/v1/stock/snapshots")).anySatisfy(s -> assertThat(s)
                .containsEntry("documentNumber", "INVRPT-S").containsEntry("linesCompared", 2)
                .containsEntry("linesMatched", 1));
        assertThat(list(company, "/api/v1/stock/alerts?sku=SKU-S")).isNotEmpty()
                .allSatisfy(a -> assertThat(a).containsEntry("sku", "SKU-S"));
        assertThat(list(other, "/api/v1/stock/snapshots")).isEmpty();
    }

    @Test
    void receiptWithoutDespatchAdviceEntersAndRaisesADispute() {
        ResponseEntity<Map> receipt = post(company, event("RECEIPT", "recadv-orphan", "RECADV-X", "DESADV-UNKNOWN",
                "2026-09-17T09:00:00Z", line("SKU-X", Map.of("accepted", 12))));
        assertThat(receipt.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertPosition("SKU-X", Map.of("AVAILABLE", 12.0, "IN_TRANSIT", 0.0), 12.0, 12.0);
        assertThat(alerts(company)).anySatisfy(a -> assertThat((String) a.get("message")).contains("no despatch advice"));
    }

    @Test
    void invalidEventsAreRejectedWithEveryProblem() {
        ResponseEntity<Map> receipt = post(company, event("RECEIPT", "bad-1", "R", null, "2026-09-17T09:00:00Z",
                line("SKU-B", Map.of())));
        assertThat(receipt.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(fields(receipt)).containsExactlyInAnyOrder("lines[0].accepted", "referenceNumber");

        ResponseEntity<Map> status = post(company, event("STATUS_CHANGE", "bad-2", "S", null, "2026-09-17T09:00:00Z",
                line("SKU-B", Map.of("quantity", 1, "fromState", "AVAILABLE", "toState", "AVAILABLE"))));
        assertThat(fields(status)).containsExactly("lines[0].toState");

        ResponseEntity<Map> adjustment = post(company, event("ADJUSTMENT", "bad-3", "A", null, "2026-09-17T09:00:00Z",
                line("SKU-B", Map.of("quantity", 0, "state", "AVAILABLE"))));
        assertThat(fields(adjustment)).containsExactly("lines[0].quantity");
    }

    @Test
    void anotherEnvironmentSeesNothing() {
        post(company, event("SHIPMENT_NOTICE_IN", "iso-desadv", "DESADV-I", null, "2026-09-18T08:00:00Z",
                line("SKU-I", Map.of("quantity", 5))));
        assertThat(list(other, "/api/v1/stock/positions?sku=SKU-I")).isEmpty();
        assertThat(list(other, "/api/v1/stock/movements?sku=SKU-I")).isEmpty();
        // The same idempotency key is free in another environment.
        assertThat(post(other, event("SHIPMENT_NOTICE_IN", "iso-desadv", "DESADV-I", null, "2026-09-18T08:00:00Z",
                line("SKU-I", Map.of("quantity", 7)))).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(list(other, "/api/v1/stock/positions?sku=SKU-I")).singleElement()
                .satisfies(p -> assertThat(((Number) ((Map<String, Object>) p.get("states")).get("IN_TRANSIT")).doubleValue())
                        .isEqualTo(7.0));
    }

    // ---------- helpers ----------

    @SafeVarargs
    private static Map<String, Object> event(String type, String key, String document, String reference,
                                             String occurredAt, Map<String, Object>... lines) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("type", type);
        body.put("idempotencyKey", key);
        body.put("documentNumber", document);
        if (reference != null) {
            body.put("referenceNumber", reference);
        }
        body.put("occurredAt", occurredAt);
        body.put("lines", List.of(lines));
        return body;
    }

    private static Map<String, Object> line(String sku, Map<String, Object> values) {
        Map<String, Object> line = new LinkedHashMap<>(values);
        line.put("sku", sku);
        line.put("location", SITE);
        return line;
    }

    private ResponseEntity<Map> post(UUID companyId, Map<String, Object> body) {
        return http.post().uri("/api/v1/stock/events").header("X-Mystix-Company-Id", companyId.toString())
                .contentType(MediaType.APPLICATION_JSON).body(body).retrieve().toEntity(Map.class);
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> list(UUID companyId, String uri) {
        ResponseEntity<List> response = http.get().uri(uri).header("X-Mystix-Company-Id", companyId.toString())
                .retrieve().toEntity(List.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return (List<Map<String, Object>>) response.getBody();
    }

    private List<Map<String, Object>> alerts(UUID companyId) {
        return list(companyId, "/api/v1/stock/alerts");
    }

    @SuppressWarnings("unchecked")
    private void assertPosition(String sku, Map<String, Double> states, double onHand, double atp) {
        Map<String, Object> position = list(company, "/api/v1/stock/positions?sku=" + sku).getFirst();
        Map<String, Object> actual = (Map<String, Object>) position.get("states");
        states.forEach((state, quantity) ->
                assertThat(((Number) actual.get(state)).doubleValue()).as(sku + " " + state).isEqualTo(quantity));
        assertThat(((Number) position.get("onHand")).doubleValue()).as(sku + " on-hand").isEqualTo(onHand);
        assertThat(((Number) position.get("availableToPromise")).doubleValue()).as(sku + " ATP").isEqualTo(atp);
    }

    @SuppressWarnings("unchecked")
    private static List<String> fields(ResponseEntity<Map> response) {
        List<String> fields = new ArrayList<>();
        for (Map<String, Object> e : (List<Map<String, Object>>) response.getBody().get("fieldErrors")) {
            fields.add((String) e.get("field"));
        }
        return fields;
    }
}
