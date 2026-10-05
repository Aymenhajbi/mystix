package ma.mystix.stock;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import ma.mystix.TestcontainersConfiguration;
import ma.mystix.shared.Sha256;
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
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.client.RestClient;

/** EDIFACT D96A stock messages end to end (ADR-0012), on synthetic fixtures. */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class StockEdifactApiTests {

    private static final String WAREHOUSE = "6110000000118";

    @Value("${local.server.port}")
    int port;

    @Autowired
    CompanyService companies;

    @Autowired
    JdbcClient jdbc;

    RestClient http;
    UUID company;

    @BeforeAll
    void registerCompany() {
        company = companies.register(new Ice("000000030000030"), "Entrepot EDI Synthetique SARL", null).id();
    }

    @BeforeEach
    void setUp() {
        http = RestClient.builder().baseUrl("http://localhost:" + port)
                .defaultStatusHandler(status -> true, (request, response) -> { }).build();
    }

    @Test
    @SuppressWarnings("unchecked")
    void d96aMessagesDriveTheStockFromDespatchToInventory() throws IOException {
        // DESADV from the supplier: 100 + 40 in transit at the delivery party (NAD+DP).
        Map<String, Object> desadv = receive("desadv-in.edi", "?direction=IN").getBody();
        assertThat(desadv).containsEntry("accepted", 1).containsEntry("rejected", 0);
        assertThat(message(desadv)).containsEntry("status", "APPLIED").containsEntry("documentNumber", "DESADV-EDI-1");
        assertState("6111000000048", "IN_TRANSIT", 100);

        // RECADV: QTY+194 accepted 92, QTY+124 damaged 5, QTY+119 short 3; second line QTY+48 received 40.
        receive("recadv.edi", "");
        assertState("6111000000048", "AVAILABLE", 92);
        assertState("6111000000048", "QUARANTINE", 5);
        assertState("6111000000048", "IN_TRANSIT", 0);
        assertState("6111000000055", "AVAILABLE", 40);

        // ORDERS without a location in the message: the default location of the call is used.
        receive("orders.edi", "?location=" + WAREHOUSE);
        assertState("6111000000048", "RESERVED", 30);
        assertState("6111000000048", "AVAILABLE", 62);

        // INVRPT adjustments: INV 4501 = 1 out of accepted (7491 = 1), 2 into damaged (7491 = 2).
        Map<String, Object> adjustment = receive("invrpt-adjustment.edi", "").getBody();
        assertThat(asMap(message(adjustment).get("result"))).containsEntry("type", "ADJUSTMENT");
        assertState("6111000000048", "AVAILABLE", 60);
        assertState("6111000000048", "QUARANTINE", 7);

        // INVRPT snapshot (QTY+145 / QTY+17 with INV 7491, LOC+18): 58 available counted, 2 lost.
        Map<String, Object> snapshot = receive("invrpt-snapshot.edi", "").getBody();
        Map<String, Object> result = asMap(message(snapshot).get("result"));
        assertThat(result).containsEntry("type", "SNAPSHOT").containsEntry("linesCompared", 4)
                .containsEntry("linesMatched", 3);
        assertThat(message(snapshot)).containsEntry("documentNumber", "INV+SNAP-1");
        assertState("6111000000048", "AVAILABLE", 58);
        assertState("6111000000048", "RESERVED", 30);
        assertState("6111000000048", "QUARANTINE", 7);

        // The same interchange again: replayed, nothing applied twice.
        Map<String, Object> again = receive("desadv-in.edi", "?direction=IN").getBody();
        assertThat(message(again)).containsEntry("status", "REPLAYED");
        assertState("6111000000048", "IN_TRANSIT", 0);

        // Each message is kept byte for byte, line breaks included.
        String text = new String(fixture("recadv.edi"), StandardCharsets.ISO_8859_1);
        String expected = text.substring(text.indexOf("UNH+"), text.indexOf('\'', text.indexOf("UNT+")) + 1);
        String sha = jdbc.sql("SELECT sha256 FROM stock_edi_message WHERE company_id = :c AND message_type = 'RECADV'")
                .param("c", company).query(String.class).single();
        assertThat(sha).isEqualTo(Sha256.hex(expected.getBytes(StandardCharsets.ISO_8859_1)));
    }

    @Test
    void eachMessageIsJudgedOnItsOwn() throws IOException {
        // Two messages, two independent outcomes: the second ORDERS carries QTY+12 instead of QTY+21.
        Map<String, Object> receipt = receive("orders-two-messages.edi", "?location=SITE-MIX").getBody();
        assertThat(receipt).containsEntry("accepted", 0).containsEntry("rejected", 2);
        // Nothing in stock at SITE-MIX: the first order is refused for insufficient stock, not silently reserved.
        List<Map<String, Object>> messages = messages(receipt);
        assertThat(messages.get(0)).containsEntry("status", "REJECTED").containsEntry("errorCode", "STOCK_INSUFFICIENT");
        assertThat(messages.get(1)).containsEntry("status", "REJECTED").containsEntry("errorCode", "EDIFACT_MAPPING_FAILED");
        assertThat((String) messages.get(1).get("errorDetail")).contains("QTY+21");
    }

    @Test
    void aDespatchAdviceNeedsItsDirectionAndABadInterchangeIsRefusedWhole() throws IOException {
        Map<String, Object> noDirection = receive("desadv-in.edi", "").getBody();
        assertThat(message(noDirection)).containsEntry("status", "REJECTED")
                .containsEntry("errorCode", "EDIFACT_MAPPING_FAILED");

        ResponseEntity<Map> bad = receive("orders-bad-count.edi", "");
        assertThat(bad.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(bad.getBody()).containsEntry("errorCode", "EDIFACT_INVALID");
        assertThat(fieldOf(bad)).isEqualTo("segment 6 UNT");
    }

    // ---------- helpers ----------

    private ResponseEntity<Map> receive(String fixture, String query) throws IOException {
        return http.post().uri("/api/v1/stock/edifact" + query)
                .header("X-Mystix-Company-Id", company.toString())
                .contentType(MediaType.parseMediaType("application/edifact"))
                .body(fixture(fixture))
                .retrieve().toEntity(Map.class);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> messages(Map<String, Object> receipt) {
        return (List<Map<String, Object>>) receipt.get("messages");
    }

    private static Map<String, Object> message(Map<String, Object> receipt) {
        return messages(receipt).getFirst();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        return (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    private static String fieldOf(ResponseEntity<Map> response) {
        return (String) ((List<Map<String, Object>>) response.getBody().get("fieldErrors")).getFirst().get("field");
    }

    @SuppressWarnings("unchecked")
    private void assertState(String sku, String state, double quantity) {
        List<Map<String, Object>> positions = http.get().uri("/api/v1/stock/positions?sku={sku}&location={loc}", sku, WAREHOUSE)
                .header("X-Mystix-Company-Id", company.toString()).retrieve().body(List.class);
        Map<String, Object> states = (Map<String, Object>) positions.getFirst().get("states");
        assertThat(((Number) states.get(state)).doubleValue()).as(sku + " " + state).isEqualTo(quantity);
    }

    private static byte[] fixture(String name) throws IOException {
        try (InputStream in = StockEdifactApiTests.class.getResourceAsStream("/fixtures/edifact/" + name)) {
            return in.readAllBytes();
        }
    }
}
