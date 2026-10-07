package ma.mystix.invoice;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import ma.mystix.TestClockConfiguration;
import ma.mystix.TestcontainersConfiguration;
import ma.mystix.shared.Sha256;
import ma.mystix.tenant.CompanyService;
import ma.mystix.tenant.Ice;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClient;

@Import({TestcontainersConfiguration.class, TestClockConfiguration.class})
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"mystix.clearance.simulated.reject-numbers-matching=FA-SIMREJECT-.*", "mystix.admin.enabled=true"})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class InvoiceApiTests {

    private static final String REFERENCE_NUMBER = "FA-2026-000123";
    private static final String FIXTURE_SELLER_ICE = "\"ice\": \"000000001000011\"";
    private static final String RATE_TEN = "\"ratePercent\": \"10.00\"";

    @Value("${local.server.port}")
    int port;

    @Autowired
    CompanyService companies;

    RestClient http;
    String validRequest;
    UUID sellerCompany;
    UUID otherCompany;
    /** Receives the invoices of the backdated VAT rate matrix. */
    UUID rateCompany;

    @BeforeAll
    void registerCompanies() {
        // Same ICE as the seller of the reference fixture.
        sellerCompany = companies.register(new Ice("000000001000011"), "Fournisseur Synthetique SARL", null).id();
        otherCompany = companies.register(new Ice("000000003000033"), "Autre Societe Synthetique", null).id();
        rateCompany = companies.register(new Ice("000000013000013"), "Environnement Taux SARL", null).id();
    }

    @BeforeEach
    void setUp() throws IOException {
        http = RestClient.builder()
                .baseUrl("http://localhost:" + port)
                .defaultStatusHandler(status -> true, (request, response) -> { })
                .build();
        validRequest = new String(resource("fixtures/api/invoice-basic.request.json"), StandardCharsets.UTF_8);
    }

    @Test
    void acceptsStoresAndReplaysTheReferenceInvoice() throws IOException {
        ResponseEntity<byte[]> first = submit(sellerCompany, validRequest, byte[].class);

        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(first.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_XML);
        assertThat(first.getHeaders().getFirst("X-Mystix-Replayed")).isEqualTo("false");
        assertThat(first.getHeaders().getFirst("X-Mystix-Canonical-Version")).isEqualTo("1.0");
        assertThat(first.getHeaders().getFirst("X-Mystix-En16931-Artefacts")).isEqualTo("1.3.16");
        assertThat(first.getBody()).isEqualTo(resource("fixtures/ubl/invoice-basic.expected.xml"));
        String id = first.getHeaders().getFirst("X-Mystix-Invoice-Id");
        assertThat(first.getHeaders().getLocation()).hasToString("/api/v1/invoices/" + id);

        // Same invoice again, reformatted: same canonical content, so a replay of the stored result.
        ResponseEntity<byte[]> replay = submit(sellerCompany, validRequest.replace("\n", "\r\n  "), byte[].class);

        assertThat(replay.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(replay.getHeaders().getFirst("X-Mystix-Replayed")).isEqualTo("true");
        assertThat(replay.getHeaders().getFirst("X-Mystix-Invoice-Id")).isEqualTo(id);
        assertThat(replay.getBody()).isEqualTo(first.getBody());

        // Simulated clearance: cleared once, the replay returns the same reference without a new clearance.
        assertThat(first.getHeaders().getFirst("X-Mystix-Status")).isEqualTo("CLEARED");
        assertThat(first.getHeaders().getFirst("X-Mystix-Clearance-Simulated")).isEqualTo("true");
        String reference = first.getHeaders().getFirst("X-Mystix-Clearance-Reference");
        assertThat(reference).startsWith("SIMULATED-");
        assertThat(replay.getHeaders().getFirst("X-Mystix-Clearance-Reference")).isEqualTo(reference);

        ResponseEntity<Map> stored = http.get().uri("/api/v1/invoices/{id}", id)
                .header("X-Mystix-Company-Id", sellerCompany.toString())
                .retrieve().toEntity(Map.class);
        assertThat(stored.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(stored.getBody())
                .containsEntry("number", REFERENCE_NUMBER)
                .containsEntry("status", "CLEARED")
                .containsEntry("canonicalVersion", "1.0");
        assertThat((Map<String, Object>) stored.getBody().get("clearance"))
                .containsEntry("reference", reference)
                .containsEntry("simulated", true);
        assertThat((List<Map<String, Object>>) stored.getBody().get("history"))
                .extracting(e -> e.get("status"))
                .containsExactly("VALIDATED", "CLEARED");
        assertThat((List<Map<String, Object>>) stored.getBody().get("artifacts"))
                .extracting(a -> a.get("kind"))
                .containsExactly("RAW", "CANONICAL", "OUT");

        ResponseEntity<byte[]> raw = artifact(sellerCompany, id, "RAW");
        assertThat(raw.getBody()).isEqualTo(validRequest.getBytes(StandardCharsets.UTF_8));
        ResponseEntity<byte[]> out = artifact(sellerCompany, id, "OUT");
        assertThat(out.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_XML);
        assertThat(out.getBody()).isEqualTo(first.getBody());
        assertThat((List<Map<String, Object>>) stored.getBody().get("artifacts"))
                .filteredOn(a -> "OUT".equals(a.get("kind")))
                .singleElement()
                .satisfies(a -> assertThat(a).containsEntry("sha256", Sha256.hex(first.getBody())));
    }

    @Test
    void simulatedClearanceRejectionIsStoredAndTraced() {
        ResponseEntity<byte[]> response = submit(sellerCompany, withNumber("FA-SIMREJECT-1"), byte[].class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getHeaders().getFirst("X-Mystix-Status")).isEqualTo("CLEARANCE_REJECTED");
        assertThat(response.getHeaders().getFirst("X-Mystix-Clearance-Simulated")).isEqualTo("true");
        assertThat(response.getHeaders().getFirst("X-Mystix-Clearance-Reference")).isNull();

        String id = response.getHeaders().getFirst("X-Mystix-Invoice-Id");
        ResponseEntity<Map> stored = http.get().uri("/api/v1/invoices/{id}", id)
                .header("X-Mystix-Company-Id", sellerCompany.toString())
                .retrieve().toEntity(Map.class);
        assertThat(stored.getBody()).containsEntry("status", "CLEARANCE_REJECTED");
        assertThat((List<Map<String, Object>>) stored.getBody().get("history"))
                .last()
                .satisfies(e -> assertThat(e)
                        .containsEntry("status", "CLEARANCE_REJECTED")
                        .hasEntrySatisfying("detail", d -> assertThat((String) d).startsWith("SIMULATED")));
    }

    @Test
    void listsOnlyTheCompanysInvoicesWithSummaries() {
        String id = submit(sellerCompany, withNumber("FA-LIST-1"), byte[].class)
                .getHeaders().getFirst("X-Mystix-Invoice-Id");

        ResponseEntity<List> mine = http.get().uri("/api/v1/invoices?limit=200")
                .header("X-Mystix-Company-Id", sellerCompany.toString())
                .retrieve().toEntity(List.class);
        ResponseEntity<List> theirs = http.get().uri("/api/v1/invoices")
                .header("X-Mystix-Company-Id", otherCompany.toString())
                .retrieve().toEntity(List.class);

        assertThat(mine.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat((List<Map<String, Object>>) mine.getBody())
                .filteredOn(i -> id.equals(i.get("id")))
                .singleElement()
                .satisfies(i -> {
                    assertThat(i)
                            .containsEntry("number", "FA-LIST-1")
                            .containsEntry("buyerName", "Client Synthetique SA")
                            .containsEntry("currency", "MAD")
                            .containsEntry("payableAmount", "1586.80")
                            .containsEntry("status", "CLEARED");
                    assertThat((Map<String, Object>) i.get("clearance")).containsEntry("simulated", true);
                });
        assertThat((List<Map<String, Object>>) theirs.getBody()).noneMatch(i -> id.equals(i.get("id")));
    }

    @Test
    void rejectedSubmissionIsLoggedWithReasonsAndPayload() {
        String request = withNumber("FA-LOG-REJ-1")
                .replace("\"gln\": \"6110000000017\"", "\"gln\": \"6110000000018\"");

        ResponseEntity<Map> rejected = submit(sellerCompany, request, Map.class);

        assertThat(rejected.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        String requestId = (String) rejected.getBody().get("requestId");
        assertThat(requestId).isNotBlank().isEqualTo(rejected.getHeaders().getFirst("X-Request-Id"));

        Map<String, Object> entry = logs(sellerCompany, "?level=ERROR").stream()
                .filter(e -> requestId.equals(e.get("requestId")))
                .findFirst().orElseThrow();
        assertThat(entry)
                .containsEntry("level", "ERROR")
                .containsEntry("event", "INVOICE_REJECTED")
                .containsEntry("errorCode", "INVOICE_REJECTED")
                .containsEntry("stage", "MAPPING")
                .containsEntry("invoiceNumber", "FA-LOG-REJ-1")
                .containsKey("occurredAt");
        assertThat(asMap(entry.get("userMessage"))).containsKeys("fr", "ar");
        assertThat((List<Map<String, Object>>) asMap(entry.get("details")).get("fieldErrors"))
                .anySatisfy(f -> assertThat(f).containsEntry("field", "seller"));
        assertThat((Integer) entry.get("payloadSize")).isEqualTo(request.getBytes(StandardCharsets.UTF_8).length);

        ResponseEntity<byte[]> payload = http.get().uri("/api/v1/logs/{id}/payload", entry.get("id"))
                .header("X-Mystix-Company-Id", sellerCompany.toString())
                .retrieve().toEntity(byte[].class);
        assertThat(payload.getBody()).isEqualTo(request.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void acceptedSubmissionLogsAcceptanceAndClearanceUnderTheCallersRequestId() {
        ResponseEntity<byte[]> created = http.post().uri("/api/v1/invoices")
                .header("X-Mystix-Company-Id", sellerCompany.toString())
                .header("X-Request-Id", "erp-batch-0001")
                .contentType(MediaType.APPLICATION_JSON)
                .body(withNumber("FA-LOG-OK-1").getBytes(StandardCharsets.UTF_8))
                .retrieve().toEntity(byte[].class);
        assertThat(created.getHeaders().getFirst("X-Request-Id")).isEqualTo("erp-batch-0001");
        String id = created.getHeaders().getFirst("X-Mystix-Invoice-Id");

        List<Map<String, Object>> entries = logs(sellerCompany, "?invoiceId=" + id);

        // Warnings (backdated fixture, VAT referential empty for its date) may come along; they do not change
        // the acceptance events, and every entry carries the caller's request id.
        assertThat(entries).filteredOn(e -> "INFO".equals(e.get("level"))).extracting(e -> e.get("event"))
                .containsExactly("CLEARANCE_CLEARED", "INVOICE_ACCEPTED");
        assertThat(entries).allSatisfy(e -> assertThat(e)
                .containsEntry("requestId", "erp-batch-0001")
                .containsEntry("invoiceNumber", "FA-LOG-OK-1"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void lineageReadsRequestCanonicalAndUblValues() {
        String id = submit(sellerCompany, withNumber("FA-LINEAGE-1"), byte[].class)
                .getHeaders().getFirst("X-Mystix-Invoice-Id");

        ResponseEntity<Map> response = http.get().uri("/api/v1/invoices/{id}/lineage", id)
                .header("X-Mystix-Company-Id", sellerCompany.toString())
                .retrieve().toEntity(Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).containsEntry("mapping", "ubl-invoice");
        List<Map<String, Object>> rows = (List<Map<String, Object>>) response.getBody().get("rows");

        Map<String, Object> number = row(rows, "BT-1", null);
        assertThat(asMap(number.get("request"))).containsEntry("value", "FA-LINEAGE-1");
        assertThat(asMap(number.get("canonical"))).containsEntry("value", "FA-LINEAGE-1");
        assertThat(asMap(number.get("target"))).containsEntry("value", "FA-LINEAGE-1");

        Map<String, Object> price = row(rows, "BT-146", 0);
        assertThat(asMap(price.get("request"))).containsEntry("value", "125.50");
        assertThat(asMap(price.get("target")))
                .containsEntry("value", "125.5")
                .containsEntry("path", "/inv:Invoice/cac:InvoiceLine[1]/cac:Price/cbc:PriceAmount");

        assertThat(asMap(row(rows, "BT-131", 1).get("target"))).containsEntry("value", "120.83");
        assertThat(row(rows, "BT-131", 1)).containsEntry("kind", "CALCULATED");

        Map<String, Object> tradeRegister = rows.stream()
                .filter(r -> "NOT_EMITTED".equals(r.get("kind"))).findFirst().orElseThrow();
        assertThat(asMap(tradeRegister.get("canonical"))).containsEntry("value", "RC-TEST-1");
        assertThat(tradeRegister.get("target")).isNull();

        ResponseEntity<Map> asOther = http.get().uri("/api/v1/invoices/{id}/lineage", id)
                .header("X-Mystix-Company-Id", otherCompany.toString())
                .retrieve().toEntity(Map.class);
        assertThat(asOther.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void mappingSpecIsPublished() {
        ResponseEntity<Map> spec = http.get().uri("/api/v1/mappings/ubl-invoice").retrieve().toEntity(Map.class);

        assertThat(spec.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(spec.getBody()).containsEntry("id", "ubl-invoice").containsKey("fields");
    }

    private static Map<String, Object> row(List<Map<String, Object>> rows, String term, Integer line) {
        return rows.stream()
                .filter(r -> term.equals(r.get("term")) && java.util.Objects.equals(line, r.get("line")))
                .findFirst().orElseThrow(() -> new AssertionError("No row " + term + " line " + line));
    }

    @Test
    @SuppressWarnings("unchecked")
    void flowsDriveWhereInvoicesGo() {
        UUID company = companies.register(new Ice("000000004000044"), "Environnement Flux SARL", null).id();
        String request = withNumber("FA-FLOW-1").replace("000000001000011", "000000004000044");

        List<Map<String, Object>> flows = getList("/api/v1/flows", company);
        assertThat(flows).singleElement().satisfies(f -> assertThat(f)
                .containsEntry("name", "Factures API vers UBL 2.1")
                .containsEntry("status", "ACTIVE")
                .containsEntry("mappingId", "ubl-invoice"));
        String defaultFlow = (String) flows.getFirst().get("id");

        ResponseEntity<byte[]> first = submit(company, request, byte[].class);
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(first.getHeaders().getFirst("X-Mystix-Flow-Id")).isEqualTo(defaultFlow);

        // A paused flow receives nothing.
        assertThat(patchFlow(company, defaultFlow, "{\"status\":\"PAUSED\"}").getStatusCode()).isEqualTo(HttpStatus.OK);
        ResponseEntity<Map> paused = submit(company, request.replace("FA-FLOW-1", "FA-FLOW-2"), Map.class);
        assertThat(paused.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(paused.getBody()).containsEntry("errorCode", "FLOW_NOT_ACTIVE");

        // A flow with planned options can be declared, but not activated until it is executable.
        ResponseEntity<Map> declared = createFlow(company, "Factures AS2", "AS2", "JSON_CANONICAL", "UBL_2_1", "API_RESPONSE");
        assertThat(declared.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(declared.getBody()).containsEntry("status", "DRAFT").containsEntry("executable", false);
        assertThat(patchFlow(company, (String) declared.getBody().get("id"), "{\"status\":\"ACTIVE\"}").getBody())
                .containsEntry("errorCode", "FLOW_NOT_EXECUTABLE");
        ResponseEntity<Map> unknown = createFlow(company, "Factures X", "FAX", "JSON_CANONICAL", "UBL_2_1", "API_RESPONSE");
        assertThat(unknown.getBody()).containsEntry("errorCode", "FLOW_OPTION_UNAVAILABLE");

        ResponseEntity<Map> created = createFlow(company, "Factures export", "API", "JSON_CANONICAL", "UBL_2_1", "API_RESPONSE");
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(created.getBody()).containsEntry("status", "DRAFT").containsEntry("mappingVersion", "1.0")
                .containsEntry("executable", true).containsEntry("direction", "OUT");
        String exportFlow = (String) created.getBody().get("id");
        assertThat(createFlow(company, "Factures export", "API", "JSON_CANONICAL", "UBL_2_1", "API_RESPONSE")
                .getStatusCode()).isEqualTo(HttpStatus.CONFLICT);

        // A draft flow named by the caller refuses; once active it takes the invoice.
        ResponseEntity<byte[]> draft = submitToFlow(company, exportFlow, request.replace("FA-FLOW-1", "FA-FLOW-3"));
        assertThat(draft.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        patchFlow(company, exportFlow, "{\"status\":\"ACTIVE\"}");
        ResponseEntity<byte[]> routed = submitToFlow(company, exportFlow, request.replace("FA-FLOW-1", "FA-FLOW-3"));
        assertThat(routed.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(routed.getHeaders().getFirst("X-Mystix-Flow-Id")).isEqualTo(exportFlow);

        assertThat(getList("/api/v1/invoices?flowId=" + exportFlow, company))
                .extracting(i -> i.get("number")).containsExactly("FA-FLOW-3");
        assertThat(getList("/api/v1/invoices?flowId=" + defaultFlow, company))
                .extracting(i -> i.get("number")).containsExactly("FA-FLOW-1");

        // Isolation: another company cannot see the flow.
        ResponseEntity<Map> foreign = http.get().uri("/api/v1/flows/{id}", exportFlow)
                .header("X-Mystix-Company-Id", otherCompany.toString()).retrieve().toEntity(Map.class);
        assertThat(foreign.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

        // Operator view across environments.
        ResponseEntity<List> environments = http.get().uri("/api/v1/admin/environments").retrieve().toEntity(List.class);
        assertThat((List<Map<String, Object>>) environments.getBody())
                .filteredOn(e -> company.toString().equals(e.get("id")))
                .singleElement()
                .satisfies(e -> assertThat(e)
                        .containsEntry("legalName", "Environnement Flux SARL")
                        .containsEntry("flows", 3)
                        .containsEntry("activeFlows", 1)
                        .containsEntry("inboundFlows", 0)
                        .containsEntry("partners", 0)
                        .containsEntry("invoices", 2));
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> getList(String uri, UUID company) {
        ResponseEntity<List> response = http.get().uri(uri)
                .header("X-Mystix-Company-Id", company.toString()).retrieve().toEntity(List.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return (List<Map<String, Object>>) response.getBody();
    }

    private ResponseEntity<Map> createFlow(UUID company, String name, String sourceChannel, String sourceFormat,
                                           String targetFormat, String targetChannel) {
        return http.post().uri("/api/v1/flows")
                .header("X-Mystix-Company-Id", company.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("name", name, "direction", "OUT", "sourceChannel", sourceChannel, "sourceFormat", sourceFormat,
                        "targetFormat", targetFormat, "targetChannel", targetChannel))
                .retrieve().toEntity(Map.class);
    }

    private ResponseEntity<Map> patchFlow(UUID company, String flowId, String json) {
        return http.patch().uri("/api/v1/flows/{id}", flowId)
                .header("X-Mystix-Company-Id", company.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .body(json)
                .retrieve().toEntity(Map.class);
    }

    private ResponseEntity<byte[]> submitToFlow(UUID company, String flowId, String body) {
        return http.post().uri("/api/v1/invoices")
                .header("X-Mystix-Company-Id", company.toString())
                .header("X-Mystix-Flow-Id", flowId)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_XML, MediaType.APPLICATION_JSON)
                .body(body.getBytes(StandardCharsets.UTF_8))
                .retrieve().toEntity(byte[].class);
    }

    @Test
    @SuppressWarnings("unchecked")
    void mappingVersionsAreTestedBeforeBeingPublishedAndRunOnTheFlow() {
        UUID company = companies.register(new Ice("000000005000055"), "Environnement Mapping SARL", null).id();
        String flowId = (String) getList("/api/v1/flows", company).getFirst().get("id");
        String base = withNumber("FA-MAP-1").replace("000000001000011", "000000005000055");
        String sapUnits = base.replace("\"unitCode\": \"C62\", \"unitPrice\": \"125.50\"",
                "\"unitCode\": \"PCE\", \"unitPrice\": \"125.50\"");
        assertThat(sapUnits).isNotEqualTo(base);

        // Without a rule, the SAP unit code PCE fails EN 16931 (UN/ECE Rec 20 code list).
        ResponseEntity<Map> refused = submit(company, sapUnits, Map.class);
        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);

        // Invalid rules are refused with their path.
        ResponseEntity<Map> invalid = mapping(company, flowId, "POST", "/versions",
                "{\"rules\":[{\"target\":\"lines.unitPrice\"}]}");
        assertThat(invalid.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(invalid.getBody()).containsEntry("errorCode", "MAPPING_RULES_INVALID");
        assertThat(fields(invalid)).containsExactly("rules[0].target");

        String rules = """
                {"rules":[
                  {"target":"lines.unitCode","transforms":[{"op":"lookup","table":{"PCE":"C62"},"fallback":"KEEP"}]},
                  {"target":"buyerReference","source":{"type":"FIELD","path":"purchaseOrderReference"},
                   "transforms":[{"op":"prefix","value":"CMD-"}]}
                ]}""";
        ResponseEntity<Map> draft = mapping(company, flowId, "POST", "/versions", rules);
        assertThat(draft.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(draft.getBody()).containsEntry("version", 1).containsEntry("status", "DRAFT");

        // Publication needs a green test on these exact rules.
        assertThat(mapping(company, flowId, "POST", "/versions/1/publish", null).getBody())
                .containsEntry("errorCode", "MAPPING_NOT_TESTED");
        ResponseEntity<Map> tested = mapping(company, flowId, "POST", "/versions/1/test", null);
        Map<String, Object> report = asMap(tested.getBody().get("testReport"));
        assertThat(report).containsEntry("passed", true).containsEntry("failures", 0);
        assertThat((List<Map<String, Object>>) report.get("samples")).first().satisfies(sample -> {
            assertThat(sample).containsEntry("label", "reference").containsEntry("passed", true);
            assertThat((List<Map<String, Object>>) sample.get("changes"))
                    .anySatisfy(c -> assertThat(c).containsEntry("term", "BT-10")
                            .containsEntry("before", "ACHATS-42").containsEntry("after", "CMD-PO-7781"));
        });
        assertThat(mapping(company, flowId, "POST", "/versions/1/publish", null).getBody())
                .containsEntry("status", "PUBLISHED");
        assertThat(mapping(company, flowId, "PUT", "/versions/1", rules).getBody())
                .containsEntry("errorCode", "MAPPING_NOT_EDITABLE");

        // The published rules now run on the flow: PCE becomes C62, the RAW request is kept as received.
        ResponseEntity<byte[]> accepted = submit(company, sapUnits, byte[].class);
        assertThat(accepted.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String ubl = new String(accepted.getBody(), StandardCharsets.UTF_8);
        assertThat(ubl).contains("unitCode=\"C62\"").contains("<cbc:BuyerReference>CMD-PO-7781</cbc:BuyerReference>");
        String invoiceId = accepted.getHeaders().getFirst("X-Mystix-Invoice-Id");
        assertThat(new String(artifact(company, invoiceId, "RAW").getBody(), StandardCharsets.UTF_8)).contains("\"PCE\"");

        // A version that breaks EN 16931 cannot be published.
        mapping(company, flowId, "POST", "/versions",
                "{\"rules\":[{\"target\":\"lines.unitCode\",\"source\":{\"type\":\"CONSTANT\",\"value\":\"ZZQ\"}}]}");
        Map<String, Object> failed = asMap(mapping(company, flowId, "POST", "/versions/2/test", null).getBody().get("testReport"));
        assertThat(failed).containsEntry("passed", false);
        assertThat(mapping(company, flowId, "POST", "/versions/2/publish", null).getBody())
                .containsEntry("errorCode", "MAPPING_TEST_FAILED");

        // Removing the conversion would break the flow's real traffic: the stored PCE invoice fails the test.
        mapping(company, flowId, "POST", "/versions", "{\"rules\":[]}");
        Map<String, Object> withoutRule = asMap(mapping(company, flowId, "POST", "/versions/3/test", null).getBody().get("testReport"));
        assertThat(withoutRule).containsEntry("passed", false);
        assertThat((List<Map<String, Object>>) withoutRule.get("samples"))
                .anySatisfy(sample -> assertThat(sample).containsEntry("label", "FA-MAP-1").containsEntry("passed", false));

        // Roll back: a new valid version is published, then the retired version 1 is published again.
        mapping(company, flowId, "PUT", "/versions/3",
                "{\"rules\":[{\"target\":\"lines.unitCode\",\"transforms\":[{\"op\":\"lookup\",\"table\":{\"PCE\":\"C62\"}}]}]}");
        mapping(company, flowId, "POST", "/versions/3/test", null);
        assertThat(mapping(company, flowId, "POST", "/versions/3/publish", null).getBody()).containsEntry("status", "PUBLISHED");
        assertThat(mapping(company, flowId, "POST", "/versions/1/publish", null).getBody()).containsEntry("status", "PUBLISHED");
        List<Map<String, Object>> versions = (List<Map<String, Object>>) http.get().uri("/api/v1/flows/{id}/mapping", flowId)
                .header("X-Mystix-Company-Id", company.toString()).retrieve().toEntity(Map.class).getBody().get("versions");
        assertThat(versions).extracting(v -> v.get("version") + ":" + v.get("status"))
                .containsExactly("3:RETIRED", "2:DRAFT", "1:PUBLISHED");

        // Isolation: another company cannot see this flow's mapping.
        assertThat(http.get().uri("/api/v1/flows/{id}/mapping", flowId)
                .header("X-Mystix-Company-Id", otherCompany.toString()).retrieve().toEntity(Map.class)
                .getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    private ResponseEntity<Map> mapping(UUID company, String flowId, String method, String path, String json) {
        var spec = http.method(org.springframework.http.HttpMethod.valueOf(method))
                .uri("/api/v1/flows/" + flowId + "/mapping" + path)
                .header("X-Mystix-Company-Id", company.toString());
        if (json != null) {
            spec = spec.contentType(MediaType.APPLICATION_JSON).body(json);
        }
        return spec.retrieve().toEntity(Map.class);
    }

    @Test
    @SuppressWarnings("unchecked")
    void partnersAndInboundFlowsBelongToTheirEnvironment() {
        UUID company = companies.register(new Ice("000000006000066"), "Environnement Partenaires SARL", null).id();

        ResponseEntity<Map> supplier = http.post().uri("/api/v1/partners")
                .header("X-Mystix-Company-Id", company.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("name", "Fournisseur Emballages", "type", "SUPPLIER", "ice", "000000007000077",
                        "gln", "6110000000017", "reference", "V-1001"))
                .retrieve().toEntity(Map.class);
        assertThat(supplier.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String supplierId = (String) supplier.getBody().get("id");

        ResponseEntity<Map> badGln = http.post().uri("/api/v1/partners")
                .header("X-Mystix-Company-Id", company.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("name", "GLN faux", "type", "CUSTOMER", "gln", "6110000000018"))
                .retrieve().toEntity(Map.class);
        assertThat(badGln.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(fields(badGln)).containsExactly("gln");

        // An inbound flow from the supplier: declared now, not executable before lot 6.
        ResponseEntity<Map> inbound = http.post().uri("/api/v1/flows")
                .header("X-Mystix-Company-Id", company.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("name", "Factures fournisseurs vers SAP", "direction", "IN", "partnerId", supplierId,
                        "sourceChannel", "AS2", "sourceFormat", "UBL_2_1", "targetFormat", "IDOC_INVOIC02",
                        "targetChannel", "SFTP"))
                .retrieve().toEntity(Map.class);
        assertThat(inbound.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(inbound.getBody())
                .containsEntry("direction", "IN")
                .containsEntry("partnerId", supplierId)
                .containsEntry("executable", false)
                .containsEntry("mappingId", null);

        String inboundId = (String) inbound.getBody().get("id");
        ResponseEntity<Map> draftOnInbound = http.post().uri("/api/v1/flows/" + inboundId + "/mapping/versions")
                .header("X-Mystix-Company-Id", company.toString())
                .retrieve().toEntity(Map.class);
        assertThat(draftOnInbound.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(draftOnInbound.getBody()).containsEntry("errorCode", "FLOW_NOT_EXECUTABLE");

        // IN options do not exist for OUT and the reverse.
        ResponseEntity<Map> wrongDirection = http.post().uri("/api/v1/flows")
                .header("X-Mystix-Company-Id", company.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("name", "Mauvais sens", "direction", "OUT", "sourceChannel", "API",
                        "sourceFormat", "UBL_2_1", "targetFormat", "IDOC_INVOIC02", "targetChannel", "SFTP"))
                .retrieve().toEntity(Map.class);
        assertThat(wrongDirection.getBody()).containsEntry("errorCode", "FLOW_OPTION_UNAVAILABLE");

        // A partner of another environment cannot be used.
        ResponseEntity<Map> foreignPartner = http.post().uri("/api/v1/flows")
                .header("X-Mystix-Company-Id", otherCompany.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("name", "Partenaire étranger", "direction", "IN", "partnerId", supplierId,
                        "sourceChannel", "AS2", "sourceFormat", "UBL_2_1", "targetFormat", "IDOC_INVOIC02",
                        "targetChannel", "SFTP"))
                .retrieve().toEntity(Map.class);
        assertThat(foreignPartner.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(foreignPartner.getBody()).containsEntry("errorCode", "PARTNER_NOT_FOUND");

        assertThat(getList("/api/v1/flows", company)).extracting(f -> f.get("direction"))
                .containsExactly("OUT", "IN");
        assertThat(getList("/api/v1/partners", company)).extracting(p -> p.get("name"))
                .containsExactly("Fournisseur Emballages");
        assertThat(getList("/api/v1/partners", otherCompany)).extracting(p -> p.get("name"))
                .doesNotContain("Fournisseur Emballages");
    }

    @Test
    @SuppressWarnings("unchecked")
    void sellerIceIsTheCompanyIceUnlessTheEnvironmentTurnsTheRuleOff() {
        UUID company = companies.register(new Ice("000000010000010"), "Environnement Vendeur SARL", null).id();
        String fixtureSellerIce = "\"ice\": \"000000001000011\"";
        assertThat(validRequest).contains(fixtureSellerIce);

        // On by default.
        ResponseEntity<Map> settings = http.get().uri("/api/v1/companies/{id}/settings", company)
                .header("X-Mystix-Company-Id", company.toString()).retrieve().toEntity(Map.class);
        assertThat(settings.getBody()).containsEntry("enforceSellerIce", true);

        // A missing seller ICE is completed with the company ICE.
        String withoutIce = withNumber("FA-SELLER-1").replace(fixtureSellerIce + ",", "");
        ResponseEntity<byte[]> completed = submit(company, withoutIce, byte[].class);
        assertThat(completed.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(new String(completed.getBody(), StandardCharsets.UTF_8))
                .contains("<cbc:CompanyID>000000010000010</cbc:CompanyID>")
                .doesNotContain("000000001000011");

        // Another seller ICE is rejected, not replaced.
        ResponseEntity<Map> mismatch = submit(company, withNumber("FA-SELLER-2"), Map.class);
        assertThat(mismatch.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(mismatch.getBody()).containsEntry("errorCode", "SELLER_ICE_MISMATCH");
        assertThat(fields(mismatch)).containsExactly("seller.ice");

        // Another environment can neither read nor change these settings.
        assertThat(http.put().uri("/api/v1/companies/{id}/settings", company)
                .header("X-Mystix-Company-Id", otherCompany.toString())
                .contentType(MediaType.APPLICATION_JSON).body(Map.of("enforceSellerIce", false))
                .retrieve().toEntity(Map.class).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

        // Turned off by the environment itself: the seller ICE is taken as sent.
        ResponseEntity<Map> off = http.put().uri("/api/v1/companies/{id}/settings", company)
                .header("X-Mystix-Company-Id", company.toString())
                .contentType(MediaType.APPLICATION_JSON).body(Map.of("enforceSellerIce", false))
                .retrieve().toEntity(Map.class);
        assertThat(off.getBody()).containsEntry("enforceSellerIce", false);
        ResponseEntity<byte[]> free = submit(company, withNumber("FA-SELLER-2"), byte[].class);
        assertThat(free.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(new String(free.getBody(), StandardCharsets.UTF_8))
                .contains("<cbc:CompanyID>000000001000011</cbc:CompanyID>");
    }


    @Test
    @SuppressWarnings("unchecked")
    void vatReferentialIsDatedAndMaintainedByTheOperator() {
        // A test country, so the Moroccan rates loaded by migration are not touched.
        for (String rate : List.of("20", "10")) {
            assertThat(addRate("TN", rate, "2031-01-01", "2031-12-31").getStatusCode()).isEqualTo(HttpStatus.CREATED);
        }
        assertThat(addRate("TN", "20.00", "2031-01-01", null).getBody()).containsEntry("errorCode", "VAT_RATE_EXISTS");
        ResponseEntity<Map> invalid = http.post().uri("/api/v1/admin/vat-rates")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("countryCode", "TN", "categoryCode", "X", "ratePercent", "12",
                        "validFrom", "2031-02-01", "validTo", "2031-01-01", "legalReference", " "))
                .retrieve().toEntity(Map.class);
        assertThat(invalid.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(fields(invalid)).containsExactlyInAnyOrder("categoryCode", "validTo", "legalReference");

        List<Map<String, Object>> inForce = http.get().uri("/api/v1/referential/vat-rates?country=TN&date=2031-06-15")
                .retrieve().body(List.class);
        assertThat(inForce).extracting(r -> ((Number) r.get("ratePercent")).doubleValue()).containsExactly(10.0, 20.0);
        assertThat(http.get().uri("/api/v1/referential/vat-rates?country=TN&date=2032-01-01").retrieve().body(List.class))
                .isEmpty();

        // Moroccan rates loaded by V12: 10 and 20 % from 2026.
        List<Map<String, Object>> morocco = http.get().uri("/api/v1/referential/vat-rates?date=2026-09-15")
                .retrieve().body(List.class);
        assertThat(morocco).extracting(r -> ((Number) r.get("ratePercent")).doubleValue()).containsExactly(10.0, 20.0);
    }

    @Test
    @SuppressWarnings("unchecked")
    void standardRatesMustBeInForceOnTheIssueDateUnlessTheEnvironmentTurnsTheCheckOff() {
        UUID company = companies.register(new Ice("000000011000011"), "Environnement TVA SARL", null).id();
        String request = withNumber("FA-VAT-1").replace(FIXTURE_SELLER_ICE + ",", "");
        assertThat(submit(company, request, byte[].class).getStatusCode()).isEqualTo(HttpStatus.CREATED);

        String fourteen = request.replace("FA-VAT-1", "FA-VAT-2").replace(RATE_TEN, "\"ratePercent\": 14");
        ResponseEntity<Map> unknown = submit(company, fourteen, Map.class);
        assertThat(unknown.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(unknown.getBody()).containsEntry("errorCode", "VAT_RATE_UNKNOWN");
        assertThat(fields(unknown)).containsExactly("lines[2].vat.ratePercent");

        // Turned off by the environment (partial update: the seller identity rule stays on).
        assertThat(settings(company, Map.of("enforceVatRates", false)))
                .containsEntry("enforceVatRates", false).containsEntry("enforceSellerIce", true);
        assertThat(submit(company, fourteen, byte[].class).getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    @Test
    @SuppressWarnings("unchecked")
    void backdatedInvoicesKeepTheRatesOfTheirIssueDateAndWaitForAnAdministrator() {
        UUID company = companies.register(new Ice("000000012000012"), "Environnement Antidate SARL", null).id();
        String issued2025 = withNumber("FA-BACK-1").replace("2026-09-15", "2025-06-15")
                .replace("2026-10-15", "2025-07-15").replace(FIXTURE_SELLER_ICE + ",", "");

        // Accepted with the 2025 rates (V12), held for validation: no clearance yet.
        ResponseEntity<byte[]> held = submit(company, issued2025, byte[].class);
        assertThat(held.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(held.getHeaders().getFirst("X-Mystix-Status")).isEqualTo("PENDING_VALIDATION");
        String id = held.getHeaders().getFirst("X-Mystix-Invoice-Id");
        assertThat(invoice(company, id)).containsEntry("status", "PENDING_VALIDATION").containsEntry("backdated", true)
                .satisfies(i -> assertThat(asMap(i.get("clearance"))).containsEntry("reference", null));
        assertThat(logs(company, "?level=WARN")).anySatisfy(e -> assertThat(e)
                .containsEntry("event", "INVOICE_BACKDATED")
                .satisfies(x -> assertThat((String) x.get("message"))
                        .contains("held for administrator validation").contains("issued 2025-06-15")));

        // 13 % was a 2024 rate only: blocked, and the reason says the invoice is backdated.
        ResponseEntity<Map> blocked = submit(company, issued2025.replace("FA-BACK-1", "FA-BACK-2")
                .replace(RATE_TEN, "\"ratePercent\": 13"), Map.class);
        assertThat(blocked.getBody()).containsEntry("errorCode", "VAT_RATE_UNKNOWN");
        assertThat(((List<Map<String, Object>>) blocked.getBody().get("fieldErrors")).getFirst().get("reason"))
                .asString().startsWith("backdated invoice (issued 2025-06-15");

        // Another environment cannot decide; the administrator approves, then clearance runs.
        assertThat(decide(otherCompany, id, true).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        ResponseEntity<Map> approved = decide(company, id, true);
        assertThat(approved.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(approved.getBody()).containsEntry("status", "CLEARED");
        assertThat(decide(company, id, false).getBody()).containsEntry("errorCode", "INVOICE_NOT_PENDING_VALIDATION");

        // Rejected: stops there.
        String other = submit(company, issued2025.replace("FA-BACK-1", "FA-BACK-3"), byte[].class)
                .getHeaders().getFirst("X-Mystix-Invoice-Id");
        assertThat(decide(company, other, false).getBody()).containsEntry("status", "VALIDATION_REJECTED");

        // No rate in the referential before 2021: accepted and held, with a warning; nothing passes silently.
        ResponseEntity<byte[]> unchecked = submit(company, issued2025.replace("FA-BACK-1", "FA-BACK-4")
                .replace("2025-06-15", "2020-06-15").replace("2025-07-15", "2020-07-15"), byte[].class);
        assertThat(unchecked.getHeaders().getFirst("X-Mystix-Status")).isEqualTo("PENDING_VALIDATION");
        assertThat(logs(company, "?level=WARN")).extracting(e -> e.get("event"))
                .contains("VAT_RATES_UNCHECKED", "INVOICE_VALIDATION_REJECTED");
    }

    private ResponseEntity<Map> addRate(String country, String rate, String from, String to) {
        Map<String, Object> body = new java.util.HashMap<>(Map.of("countryCode", country, "categoryCode", "S",
                "ratePercent", rate, "validFrom", from, "legalReference", "TEST synthetic"));
        if (to != null) {
            body.put("validTo", to);
        }
        return http.post().uri("/api/v1/admin/vat-rates").contentType(MediaType.APPLICATION_JSON).body(body)
                .retrieve().toEntity(Map.class);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> settings(UUID company, Map<String, Object> changes) {
        return http.put().uri("/api/v1/companies/{id}/settings", company)
                .header("X-Mystix-Company-Id", company.toString())
                .contentType(MediaType.APPLICATION_JSON).body(changes)
                .retrieve().body(Map.class);
    }

    private ResponseEntity<Map> decide(UUID company, String invoiceId, boolean approve) {
        return http.post().uri("/api/v1/invoices/{id}/validation", invoiceId)
                .header("X-Mystix-Company-Id", company.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("approve", approve, "comment", "TEST decision"))
                .retrieve().toEntity(Map.class);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> invoice(UUID company, String invoiceId) {
        return http.get().uri("/api/v1/invoices/{id}", invoiceId)
                .header("X-Mystix-Company-Id", company.toString())
                .retrieve().body(Map.class);
    }

    /**
     * Backdated invoices are checked against the VAT rates in force on their issue date (V12, Grant Thornton notes
     * on LF 2021-2026). The test clock starts on 2026-09-15: every earlier date is backdated and, when compliant,
     * waits for the administrator; a non-compliant rate is blocked with the rates of that period.
     */
    @ParameterizedTest(name = "{0} at {1} % -> {2}")
    @CsvSource({
            // before the reform: 7, 10, 14, 20
            "2023-06-15, 14, CONFORME",
            "2023-06-15, 16, NON_CONFORME",
            // 2024: 7, 8, 10, 11, 12, 13, 14, 16, 20
            "2024-03-15, 16, CONFORME",
            "2024-03-15, 8, CONFORME",
            "2024-03-15, 18, NON_CONFORME",
            "2024-03-15, 9, NON_CONFORME",
            // 2025: 7, 9, 10, 12, 14, 15, 18, 20
            "2025-06-15, 18, CONFORME",
            "2025-06-15, 9, CONFORME",
            "2025-06-15, 16, NON_CONFORME",
            "2025-06-15, 13, NON_CONFORME",
            // boundary of the reform: 9 % stops on 2025-12-31
            "2025-12-31, 9, CONFORME",
            "2026-01-01, 9, NON_CONFORME",
            // 2026 (backdated before the test clock): 10, 20
            "2026-01-15, 10, CONFORME",
            "2026-01-15, 14, NON_CONFORME",
            // issued on the day it is received: not backdated
            "2026-09-15, 10, CONFORME",
            "2026-09-15, 14, NON_CONFORME"
    })
    @SuppressWarnings("unchecked")
    void backdatedInvoicesAreCheckedAgainstTheVatRatesOfTheirIssueDate(String issueDate, String rate, String expected) {
        boolean backdated = issueDate.compareTo("2026-09-15") < 0;
        String request = withNumber("FA-RATE-" + issueDate + "-" + rate)
                .replace("\"issueDate\": \"2026-09-15\"", "\"issueDate\": \"" + issueDate + "\"")
                .replace(FIXTURE_SELLER_ICE + ",", "")
                .replace(RATE_TEN, "\"ratePercent\": " + rate);
        assertThat(request).contains("\"issueDate\": \"" + issueDate + "\"").contains("\"ratePercent\": " + rate);

        if ("CONFORME".equals(expected)) {
            ResponseEntity<byte[]> accepted = submit(rateCompany, request, byte[].class);
            assertThat(accepted.getStatusCode()).isEqualTo(HttpStatus.CREATED);
            // Backdated: held for the administrator; same day: straight to (simulated) clearance.
            assertThat(accepted.getHeaders().getFirst("X-Mystix-Status"))
                    .isEqualTo(backdated ? "PENDING_VALIDATION" : "CLEARED");
            assertThat(invoice(rateCompany, accepted.getHeaders().getFirst("X-Mystix-Invoice-Id")))
                    .containsEntry("backdated", backdated);
        } else {
            ResponseEntity<Map> rejected = submit(rateCompany, request, Map.class);
            assertThat(rejected.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(rejected.getBody()).containsEntry("errorCode", "VAT_RATE_UNKNOWN");
            Map<String, Object> violation = ((List<Map<String, Object>>) rejected.getBody().get("fieldErrors")).getFirst();
            assertThat(violation).containsEntry("field", "lines[2].vat.ratePercent");
            String reason = (String) violation.get("reason");
            assertThat(reason).contains(rate + " % is not a standard VAT rate in force on " + issueDate).contains("in force:");
            if (backdated) {
                assertThat(reason).startsWith("backdated invoice (issued " + issueDate);
            } else {
                assertThat(reason).doesNotContain("backdated");
            }
        }
    }

    @Test
    void statsCountSubmissionsPerEventAndStage() {
        long acceptedBefore = stat(sellerCompany, "INVOICE_ACCEPTED", "STORAGE", null);
        long mappingBefore = stat(sellerCompany, "INVOICE_REJECTED", "MAPPING", "INVOICE_REJECTED");

        submit(sellerCompany, withNumber("FA-STATS-OK-1"), byte[].class);
        submit(sellerCompany, withNumber("FA-STATS-KO-1")
                .replace("\"gln\": \"6110000000017\"", "\"gln\": \"6110000000018\""), Map.class);

        assertThat(stat(sellerCompany, "INVOICE_ACCEPTED", "STORAGE", null)).isEqualTo(acceptedBefore + 1);
        assertThat(stat(sellerCompany, "INVOICE_REJECTED", "MAPPING", "INVOICE_REJECTED")).isEqualTo(mappingBefore + 1);

        ResponseEntity<Map> invalid = http.get().uri("/api/v1/logs/stats?window=yesterday")
                .header("X-Mystix-Company-Id", sellerCompany.toString())
                .retrieve().toEntity(Map.class);
        assertThat(invalid.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @SuppressWarnings("unchecked")
    private long stat(UUID company, String event, String stage, String errorCode) {
        ResponseEntity<Map> response = http.get().uri("/api/v1/logs/stats?window=PT1H")
                .header("X-Mystix-Company-Id", company.toString())
                .retrieve().toEntity(Map.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).containsKeys("since", "until");
        return ((List<Map<String, Object>>) response.getBody().get("stats")).stream()
                .filter(s -> event.equals(s.get("event")) && stage.equals(s.get("stage"))
                        && java.util.Objects.equals(errorCode, s.get("errorCode")))
                .mapToLong(s -> ((Number) s.get("count")).longValue())
                .sum();
    }

    @Test
    void logsAreIsolatedPerCompany() {
        ResponseEntity<Map> rejected = submit(sellerCompany, "{ not json", Map.class);
        String requestId = (String) rejected.getBody().get("requestId");

        assertThat(logs(sellerCompany, "")).anyMatch(e -> requestId.equals(e.get("requestId")));
        assertThat(logs(otherCompany, "")).noneMatch(e -> requestId.equals(e.get("requestId")));
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> logs(UUID company, String query) {
        ResponseEntity<List> response = http.get().uri("/api/v1/logs" + query)
                .header("X-Mystix-Company-Id", company.toString())
                .retrieve().toEntity(List.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return (List<Map<String, Object>>) response.getBody();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        return (Map<String, Object>) value;
    }

    @Test
    void sameNumberWithDifferentContentIsAConflict() {
        String original = withNumber("FA-CONFLICT-1");
        assertThat(submit(sellerCompany, original, byte[].class).getStatusCode()).isEqualTo(HttpStatus.CREATED);

        ResponseEntity<Map> conflict = submit(sellerCompany,
                original.replace("Synthetic test invoice & fixture", "Changed note"), Map.class);

        assertThat(conflict.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(conflict.getBody()).containsEntry("errorCode", "INVOICE_NUMBER_CONFLICT");
    }

    @Test
    void aCompanyCannotReadAnotherCompanysInvoice() {
        ResponseEntity<byte[]> created = submit(sellerCompany, withNumber("FA-ISOLATION-1"), byte[].class);
        String id = created.getHeaders().getFirst("X-Mystix-Invoice-Id");

        ResponseEntity<Map> asOther = http.get().uri("/api/v1/invoices/{id}", id)
                .header("X-Mystix-Company-Id", otherCompany.toString())
                .retrieve().toEntity(Map.class);
        ResponseEntity<Map> artifactAsOther = http.get().uri("/api/v1/invoices/{id}/artifacts/RAW", id)
                .header("X-Mystix-Company-Id", otherCompany.toString())
                .retrieve().toEntity(Map.class);

        assertThat(asOther.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(asOther.getBody()).containsEntry("errorCode", "INVOICE_NOT_FOUND");
        assertThat(artifactAsOther.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void sellerIceMustBeTheSubmittingCompany() {
        ResponseEntity<Map> response = submit(otherCompany, withNumber("FA-WRONG-SELLER"), Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).containsEntry("errorCode", "SELLER_ICE_MISMATCH");
        assertThat(fields(response)).containsExactly("seller.ice");
    }

    @Test
    void companyHeaderIsRequiredAndMustExist() {
        ResponseEntity<Map> missing = http.post().uri("/api/v1/invoices")
                .contentType(MediaType.APPLICATION_JSON)
                .body(validRequest)
                .retrieve().toEntity(Map.class);
        ResponseEntity<Map> unknown = submit(UUID.randomUUID(), withNumber("FA-UNKNOWN-CO"), Map.class);

        assertThat(missing.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(fields(missing)).containsExactly("X-Mystix-Company-Id");
        assertThat(unknown.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(unknown.getBody()).containsEntry("errorCode", "COMPANY_NOT_FOUND");
    }

    @Test
    void reportsCanonicalViolationsWithTheirJsonPath() {
        String request = withNumber("FA-BAD-GS1")
                .replace("\"gln\": \"6110000000017\"", "\"gln\": \"6110000000018\"")
                .replace("\"gtin\": \"6110000000109\"", "\"gtin\": \"6110000000100\"");

        ResponseEntity<Map> response = submit(sellerCompany, request, Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody())
                .containsEntry("errorCode", "INVOICE_REJECTED")
                .containsEntry("stage", "MAPPING");
        assertThat(fields(response)).containsExactlyInAnyOrder("seller", "lines[0]");
    }

    @Test
    void reportsMissingAndNestedFields() {
        String request = validRequest
                .replace("\"number\": \"FA-2026-000123\",", "")
                .replace("\"countryCode\": \"MA\" }\n  },\n  \"buyer\"", "\"countryCode\": \"Maroc\" }\n  },\n  \"buyer\"");

        ResponseEntity<Map> response = submit(sellerCompany, request, Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).containsEntry("errorCode", "VALIDATION_FAILED");
        assertThat(fields(response)).contains("number", "seller.address.countryCode");
    }

    @Test
    void rejectsUnreadableJson() {
        ResponseEntity<Map> response = submit(sellerCompany, "{ not json", Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).containsEntry("errorCode", "VALIDATION_FAILED");
    }

    @Test
    void returnsEn16931RuleViolations() {
        // EN 16931 BR-S-05: a standard rated (S) line must have a VAT rate greater than zero. A 0 % standard rate is
        // also outside the VAT referential, so its check is turned off here to reach the EN 16931 rules.
        settings(sellerCompany, Map.of("enforceVatRates", false));
        try {
            returnsEn16931RuleViolationsWithoutReferentialCheck();
        } finally {
            settings(sellerCompany, Map.of("enforceVatRates", true));
        }
    }

    private void returnsEn16931RuleViolationsWithoutReferentialCheck() {
        String request = withNumber("FA-BR-S-05").replace(
                "\"vat\": { \"category\": \"S\", \"ratePercent\": \"10.00\" }",
                "\"vat\": { \"category\": \"S\", \"ratePercent\": 0 }");

        ResponseEntity<Map> response = submit(sellerCompany, request, Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_JSON);
        assertThat(response.getBody())
                .containsEntry("errorCode", "INVOICE_RULES_VIOLATED")
                .containsEntry("stage", "VALIDATION");
        assertThat((List<Map<String, Object>>) response.getBody().get("ruleViolations"))
                .anySatisfy(v -> assertThat(v)
                        .containsEntry("ruleId", "BR-S-05")
                        .containsEntry("severity", "FATAL"));
    }

    private String withNumber(String number) {
        String request = validRequest.replace(REFERENCE_NUMBER, number);
        assertThat(request).isNotEqualTo(validRequest);
        return request;
    }

    private <T> ResponseEntity<T> submit(UUID company, String body, Class<T> type) {
        return http.post().uri("/api/v1/invoices")
                .header("X-Mystix-Company-Id", company.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_XML, MediaType.APPLICATION_JSON)
                .body(body.getBytes(StandardCharsets.UTF_8))
                .retrieve()
                .toEntity(type);
    }

    private ResponseEntity<byte[]> artifact(UUID company, String id, String kind) {
        return http.get().uri("/api/v1/invoices/{id}/artifacts/{kind}", id, kind)
                .header("X-Mystix-Company-Id", company.toString())
                .retrieve().toEntity(byte[].class);
    }

    @SuppressWarnings("unchecked")
    private static List<String> fields(ResponseEntity<Map> response) {
        return ((List<Map<String, Object>>) response.getBody().get("fieldErrors")).stream()
                .map(e -> (String) e.get("field"))
                .toList();
    }

    private static byte[] resource(String path) throws IOException {
        try (InputStream in = InvoiceApiTests.class.getClassLoader().getResourceAsStream(path)) {
            assertThat(in).as(path).isNotNull();
            return in.readAllBytes();
        }
    }
}
