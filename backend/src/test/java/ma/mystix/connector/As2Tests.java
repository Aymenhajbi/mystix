package ma.mystix.connector;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;

import com.helger.as2lib.client.AS2Client;
import com.helger.as2lib.client.AS2ClientRequest;
import com.helger.as2lib.client.AS2ClientResponse;
import com.helger.as2lib.client.AS2ClientSettings;
import com.helger.as2lib.crypto.ECryptoAlgorithmCrypt;
import com.helger.as2lib.crypto.ECryptoAlgorithmSign;
import com.helger.as2lib.crypto.IMICMatchingHandler;
import com.helger.as2lib.disposition.DispositionOptions;
import com.helger.as2lib.message.AS2MessageMDN;
import com.helger.as2lib.message.IMessage;
import com.helger.security.keystore.EKeyStoreType;
import com.sun.net.httpserver.HttpServer;
import ma.mystix.TestcontainersConfiguration;
import ma.mystix.flow.FlowService;
import ma.mystix.tenant.CompanyService;
import ma.mystix.tenant.Ice;
import org.apache.hc.core5.util.Timeout;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;

/**
 * AS2 (RFC 4130, ADR-0004) end to end with as2-lib on both sides, over real HTTP: a partner sends signed and
 * encrypted invoices to a Mystix AS2 listener and checks the signed MDN; Mystix sends to a partner, here another
 * Mystix environment. The controls are those of the as2-test bench: nominal exchange with signed MDN and matching MIC,
 * duplicate Message-ID (same MDN, no reprocessing), unknown signer, message altered in transit, unknown AS2-To.
 * Keys and certificates are generated for the run; nothing is mocked.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "mystix.connectivity.heartbeat=200ms", "mystix.connectivity.reconcile=1h", "mystix.queue.poll-interval=50ms"})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@SuppressWarnings({"unchecked", "rawtypes"})
class As2Tests {

    private static final String REFERENCE_NUMBER = "FA-2026-000123";
    private static final String MYSTIX = "MYSTIX-TEST";
    private static final String PARTNER = "PARTNER-TEST";
    static final Path SECRETS;

    static {
        try {
            SECRETS = Files.createTempDirectory("mystix-as2-secrets");
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @DynamicPropertySource
    static void secrets(DynamicPropertyRegistry registry) {
        registry.add("mystix.secrets.directory", SECRETS::toString);
    }

    @Value("${local.server.port}")
    int port;

    @Autowired
    CompanyService companies;

    @Autowired
    FlowService flows;

    RestClient http;
    UUID company;
    UUID flowId;
    String invoiceJson;
    KeyPair mystixKey;
    X509Certificate mystixCert;
    KeyPair partnerKey;
    X509Certificate partnerCert;
    KeyPair intruderKey;
    X509Certificate intruderCert;
    UUID channelId;
    String listenerId;
    HttpServer tamperingProxy;
    int counter;

    @BeforeAll
    void setUp() throws Exception {
        http = RestClient.builder().baseUrl("http://localhost:" + port)
                .defaultStatusHandler(status -> true, (request, response) -> { }).build();
        company = companies.list().stream().filter(c -> c.ice().equals(new Ice("000000001000011"))).findFirst()
                .orElseGet(() -> companies.register(new Ice("000000001000011"), "Fournisseur File Synthetique SARL",
                        null)).id();
        flowId = flows.forSubmission(company, null).id();
        try (InputStream in = getClass().getClassLoader().getResourceAsStream("fixtures/api/invoice-basic.request.json")) {
            invoiceJson = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        mystixKey = rsa();
        mystixCert = certificate(mystixKey, MYSTIX);
        partnerKey = rsa();
        partnerCert = certificate(partnerKey, PARTNER);
        intruderKey = rsa();
        intruderCert = certificate(intruderKey, PARTNER);
        Files.writeString(SECRETS.resolve("as2-mystix-key"), pem("PRIVATE" + " KEY", mystixKey.getPrivate().getEncoded()));

        // Mystix side: an AS2 channel (both ways), its certificates, an entry point and a started AS2 listener.
        channelId = channel("AS2 partenaire test", "BOTH", "http://127.0.0.1:1/unused");
        listenerId = startListener(channelId, "AS2-IN");

        // Between partner and Mystix, a proxy that flips one byte of the body: a message altered in transit.
        tamperingProxy = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        HttpClient forward = HttpClient.newHttpClient();
        tamperingProxy.createContext("/", exchange -> {
            byte[] body = exchange.getRequestBody().readAllBytes();
            // One byte flipped in the middle of the encrypted content.
            body[body.length / 2] ^= (byte) 0xFF;
            HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port
                    + exchange.getRequestURI())).POST(HttpRequest.BodyPublishers.ofByteArray(body));
            exchange.getRequestHeaders().forEach((name, values) -> {
                if (!Set.of("host", "content-length", "connection", "transfer-encoding")
                        .contains(name.toLowerCase())) {
                    values.forEach(v -> request.header(name, v));
                }
            });
            try {
                HttpResponse<byte[]> response = forward.send(request.build(), HttpResponse.BodyHandlers.ofByteArray());
                response.headers().map().forEach((name, values) -> {
                    if (!Set.of("content-length", "transfer-encoding", "connection", ":status").contains(name)) {
                        values.forEach(v -> exchange.getResponseHeaders().add(name, v));
                    }
                });
                exchange.sendResponseHeaders(response.statusCode(), response.body().length);
                exchange.getResponseBody().write(response.body());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                exchange.close();
            }
        });
        tamperingProxy.start();
    }

    @AfterAll
    void tearDown() {
        tamperingProxy.stop(0);
    }

    @Test
    void theNineControlsOfTheBench() throws Exception {
        String url = "http://localhost:" + port + "/inbound/as2/" + listenerId;

        // 1-5. Nominal: signed SHA-256, encrypted AES-256, HTTP 200, signed MDN, processed, Original-Message-ID, MIC.
        String messageId = "<MX-TEST-" + UUID.randomUUID() + "@partner.test>";
        String number = number();
        Sent first = send(partnerKey, partnerCert, MYSTIX, url, invoice(number), messageId);
        assertThat(first.response.hasException()).as(first.response.getAsString()).isFalse();
        assertThat(first.response.getMDNVerificationCertificate()).as("MDN signed by Mystix").isEqualTo(mystixCert);
        assertThat(first.response.getMDNDisposition()).endsWith("processed");
        assertThat(first.response.getMDN().attrs().getAsString(AS2MessageMDN.MDNA_ORIG_MESSAGEID)).isEqualTo(messageId);
        assertThat(first.micMatched.get()).as("MIC of the MDN = MIC computed by the partner").isTrue();
        Map received = events().stream().filter(e -> "MESSAGE_RECEIVED".equals(e.get("eventType"))
                && number.equals(((Map) e.get("details")).get("number"))).findFirst().orElseThrow();
        assertThat((Map) received.get("details")).containsEntry("transport", "AS2").containsEntry("messageId", messageId);
        String invoiceId = (String) ((Map) received.get("details")).get("invoiceId");
        waitFor("/api/v1/invoices/" + invoiceId, i -> "CLEARED".equals(i.get("status")));

        // 6. Duplicate: same Message-ID again, same MDN back, nothing processed again.
        long receivedBefore = ((Number) listener().get("receivedCount")).longValue();
        Sent duplicate = send(partnerKey, partnerCert, MYSTIX, url, invoice(number()), messageId);
        assertThat(duplicate.response.getMDNMessageID()).isEqualTo(first.response.getMDNMessageID());
        assertThat(duplicate.response.getMDNDisposition()).endsWith("processed");
        assertThat(((Number) listener().get("receivedCount")).longValue()).isEqualTo(receivedBefore);
        assertThat(events()).anySatisfy(e -> assertThat(e.get("eventType")).isEqualTo("MESSAGE_DUPLICATE"));

        // 7. Signed by a key Mystix does not know for this partner (same AS2 name, other key): refused.
        Sent intruder = send(intruderKey, intruderCert, MYSTIX, url, invoice(number()), null);
        assertThat(intruder.response.getMDNDisposition().toLowerCase()).contains("processed/error")
                .containsAnyOf("authentication-failed", "integrity-check-failed");

        // 8. Altered in transit: refused.
        String proxied = "http://127.0.0.1:" + tamperingProxy.getAddress().getPort() + "/inbound/as2/" + listenerId;
        Sent altered = send(partnerKey, partnerCert, MYSTIX, proxied, invoice(number()), null);
        assertThat(altered.response.getMDNDisposition().toLowerCase()).contains("processed/error")
                .containsAnyOf("decryption-failed", "authentication-failed", "integrity-check-failed");

        // 9. Addressed to another AS2 identifier: refused.
        Sent unknown = send(partnerKey, partnerCert, "GMN-EDI-PROD", url, invoice(number()), null);
        assertThat(unknown.response.getMDNDisposition().toLowerCase()).contains("processed/error");

        // Refusals journaled, without the pipeline ever seeing them.
        assertThat(events()).filteredOn(e -> "MESSAGE_REFUSED".equals(e.get("eventType"))).hasSizeGreaterThanOrEqualTo(3);
        List<Map> journal = http.get().uri("/api/v1/connectivity/channels/" + channelId + "/as2/messages")
                .header("X-Mystix-Company-Id", company.toString()).retrieve().body(List.class);
        assertThat(journal).filteredOn(m -> messageId.equals(m.get("messageId"))).hasSize(1);
    }

    @Test
    void mystixSendsAnInvoiceAndChecksTheSignedMdn() throws Exception {
        // The partner here is another Mystix AS2 listener: its key is the partner's, and it knows Mystix's certificate.
        Files.writeString(SECRETS.resolve("as2-partner-key"), pem("PRIVATE" + " KEY", partnerKey.getPrivate().getEncoded()));
        UUID partnerChannel = channel("AS2 côté partenaire", "IN", null, PARTNER, MYSTIX, "as2-partner-key",
                partnerCert, mystixCert);
        String partnerListener = startListener(partnerChannel, "AS2-PART");
        UUID outbound = channel("AS2 sortant vers partenaire", "OUT",
                "http://localhost:" + port + "/inbound/as2/" + partnerListener);

        ResponseEntity<byte[]> created = http.post().uri("/api/v1/invoices").header("X-Mystix-Company-Id", company.toString())
                .contentType(MediaType.APPLICATION_JSON).accept(MediaType.APPLICATION_XML)
                .body(invoice(number()).getBytes(StandardCharsets.UTF_8)).retrieve().toEntity(byte[].class);
        String invoiceId = created.getHeaders().getFirst("X-Mystix-Invoice-Id");

        ResponseEntity<Map> sent = send(HttpMethod.POST, "/api/v1/connectivity/channels/" + outbound + "/as2/send",
                Map.of("invoiceId", invoiceId));
        assertThat(sent.getStatusCode()).as(String.valueOf(sent.getBody())).isEqualTo(HttpStatus.OK);
        assertThat(sent.getBody()).containsEntry("delivered", true).containsEntry("mdnSigned", true)
                .containsEntry("micMatched", true);
        assertThat((String) sent.getBody().get("disposition")).endsWith("processed");
        List<Map> journal = http.get().uri("/api/v1/connectivity/channels/" + outbound + "/as2/messages")
                .header("X-Mystix-Company-Id", company.toString()).retrieve().body(List.class);
        assertThat(journal).singleElement().satisfies(m -> {
            assertThat(m).containsEntry("direction", "OUT").containsEntry("micMatched", true);
            assertThat(m.get("errorCode")).isNull();
        });
    }

    @Test
    void anAs2ListenerWithoutItsKeysDoesNotStart() {
        UUID bare = channel("AS2 incomplet", "IN", null, MYSTIX, PARTNER, "as2-absent", null, null);
        ResponseEntity<Map> entry = send(HttpMethod.POST, "/api/v1/connectivity/entry-points", Map.of("code", "AS2-BARE",
                "name", "AS2 incomplet", "flowId", flowId, "documentType", "INVOICE", "inputFormat", "JSON"));
        ResponseEntity<Map> listener = send(HttpMethod.POST, "/api/v1/connectivity/listeners", Map.of("name", "AS2 incomplet",
                "kind", "AS2_SERVER", "channelId", bare, "entryPointId", entry.getBody().get("id")));
        ResponseEntity<Map> refused = send(HttpMethod.POST,
                "/api/v1/connectivity/listeners/" + listener.getBody().get("id") + "/start", null);
        assertThat(refused.getBody()).containsEntry("errorCode", "LISTENER_NOT_READY");
        assertThat((List<Map>) refused.getBody().get("fieldErrors")).extracting(f -> f.get("field"))
                .contains("secret", "certificate.own", "certificate.partner");
    }

    // ---------- helpers ----------

    private record Sent(AS2ClientResponse response, AtomicReference<Boolean> micMatched) {
    }

    /** The partner's AS2 client (as2-lib), signing with {@code key} and encrypting for Mystix. */
    private Sent send(KeyPair key, X509Certificate cert, String to, String url, String body, String messageId)
            throws Exception {
        KeyStore store = KeyStore.getInstance("PKCS12");
        store.load(null, null);
        store.setKeyEntry(PARTNER, key.getPrivate(), "pw".toCharArray(), new X509Certificate[] {cert});
        store.setCertificateEntry(to, mystixCert);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        store.store(bytes, "pw".toCharArray());

        AtomicReference<Boolean> matched = new AtomicReference<>();
        AS2ClientSettings settings = new AS2ClientSettings();
        settings.setKeyStore(EKeyStoreType.PKCS12, bytes.toByteArray(), "pw");
        settings.setSenderData(PARTNER, "as2@partner.test", PARTNER);
        settings.setReceiverData(to, to, url);
        settings.setReceiverCertificate(mystixCert);
        settings.setPartnershipName("test-" + UUID.randomUUID());
        settings.setEncryptAndSign(ECryptoAlgorithmCrypt.CRYPT_AES256_CBC, ECryptoAlgorithmSign.DIGEST_SHA_256);
        settings.setMDNOptions(new DispositionOptions().setProtocolImportance(DispositionOptions.IMPORTANCE_REQUIRED)
                .setProtocol(DispositionOptions.PROTOCOL_PKCS7_SIGNATURE)
                .setMICAlgImportance(DispositionOptions.IMPORTANCE_REQUIRED).setMICAlg(ECryptoAlgorithmSign.DIGEST_SHA_256));
        settings.setRetryCount(0);
        settings.setConnectTimeout(Timeout.ofSeconds(5));
        settings.setResponseTimeout(Timeout.ofSeconds(30));
        if (messageId != null) {
            settings.setMessageIDFormat(messageId.substring(1, messageId.length() - 1));
        }
        settings.setMICMatchingHandler(new IMICMatchingHandler() {
            @Override
            public void onMICMatch(IMessage message, String mic) {
                matched.set(true);
            }

            @Override
            public void onMICMismatch(IMessage message, String original, String received) {
                matched.set(false);
            }
        });
        AS2ClientRequest request = new AS2ClientRequest("Facture test").setData(body.getBytes(StandardCharsets.UTF_8))
                .setContentType("application/json").setFilename("facture.json");
        return new Sent(new AS2Client().sendSynchronous(settings, request), matched);
    }

    private UUID channel(String name, String direction, String url) {
        return channel(name, direction, url, MYSTIX, PARTNER, "as2-mystix-key", mystixCert, partnerCert);
    }

    private UUID channel(String name, String direction, String url, String local, String partner, String secret,
                         X509Certificate own, X509Certificate other) {
        Map<String, Object> settings = new java.util.HashMap<>(Map.of("localAs2Id", local, "partnerAs2Id", partner,
                "signing", "SHA-256", "encryption", "AES256_CBC", "mdn", "SYNC", "mdnSigned", true));
        if (url != null) {
            settings.put("url", url);
        }
        ResponseEntity<Map> r = send(HttpMethod.POST, "/api/v1/channels", Map.of("name", name, "protocol", "AS2",
                "direction", direction, "status", "ACTIVE", "usedByMystix", true, "credentialRef", secret,
                "settings", settings));
        assertThat(r.getStatusCode()).as(String.valueOf(r.getBody())).isEqualTo(HttpStatus.CREATED);
        UUID id = UUID.fromString((String) r.getBody().get("id"));
        if (own != null) {
            certificate(id, "OWN", own);
        }
        if (other != null) {
            certificate(id, "PARTNER", other);
        }
        return id;
    }

    private void certificate(UUID channel, String role, X509Certificate cert) {
        try {
            ResponseEntity<Map> r = send(HttpMethod.POST, "/api/v1/channels/" + channel + "/certificates",
                    Map.of("role", role, "certificate", pem("CERTIFICATE", cert.getEncoded())));
            assertThat(r.getStatusCode()).as(String.valueOf(r.getBody())).isEqualTo(HttpStatus.CREATED);
        } catch (java.security.cert.CertificateEncodingException e) {
            throw new IllegalStateException(e);
        }
    }

    private String startListener(UUID channel, String code) {
        ResponseEntity<Map> entry = send(HttpMethod.POST, "/api/v1/connectivity/entry-points", Map.of("code", code,
                "name", "Factures " + code, "flowId", flowId, "documentType", "INVOICE", "inputFormat", "JSON"));
        assertThat(entry.getStatusCode()).as(String.valueOf(entry.getBody())).isEqualTo(HttpStatus.CREATED);
        ResponseEntity<Map> created = send(HttpMethod.POST, "/api/v1/connectivity/listeners", Map.of("name", "AS2 " + code,
                "kind", "AS2_SERVER", "channelId", channel, "entryPointId", entry.getBody().get("id")));
        assertThat(created.getStatusCode()).as(String.valueOf(created.getBody())).isEqualTo(HttpStatus.CREATED);
        String id = (String) created.getBody().get("id");
        assertThat(created.getBody()).containsEntry("endpoint", "/inbound/as2/" + id);
        ResponseEntity<Map> started = send(HttpMethod.POST, "/api/v1/connectivity/listeners/" + id + "/start", null);
        assertThat(started.getStatusCode()).as(String.valueOf(started.getBody())).isEqualTo(HttpStatus.OK);
        return id;
    }

    private Map listener() {
        return get("/api/v1/connectivity/listeners/" + listenerId);
    }

    private List<Map> events() {
        return http.get().uri("/api/v1/connectivity/listeners/" + listenerId + "/events?limit=200")
                .header("X-Mystix-Company-Id", company.toString()).retrieve().body(List.class);
    }

    private static KeyPair rsa() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        return generator.generateKeyPair();
    }

    private static X509Certificate certificate(KeyPair key, String cn) throws Exception {
        X500Name name = new X500Name("CN=" + cn + " (fictif), O=Synthetic, C=MA");
        Instant now = Instant.now();
        var builder = new JcaX509v3CertificateBuilder(name, BigInteger.valueOf(now.toEpochMilli()),
                Date.from(now.minus(1, ChronoUnit.DAYS)), Date.from(now.plus(365, ChronoUnit.DAYS)), name, key.getPublic());
        return new JcaX509CertificateConverter().getCertificate(builder.build(
                new JcaContentSignerBuilder("SHA256withRSA").build(key.getPrivate())));
    }

    private static String pem(String label, byte[] der) {
        return "-----BEGIN " + label + "-----\n" + Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII))
                .encodeToString(der) + "\n-----END " + label + "-----\n";
    }

    private synchronized String number() {
        return "FA-AS2-" + System.nanoTime() % 1_000_000_000 + "-" + (++counter);
    }

    private String invoice(String number) {
        LocalDate today = LocalDate.now(ZoneId.of("Africa/Casablanca"));
        return invoiceJson.replace(REFERENCE_NUMBER, number)
                .replaceAll("\"issueDate\"\\s*:\\s*\"[0-9-]+\"", "\"issueDate\": \"" + today + "\"")
                .replaceAll("\"dueDate\"\\s*:\\s*\"[0-9-]+\"", "\"dueDate\": \"" + today.plusDays(30) + "\"");
    }

    private ResponseEntity<Map> send(HttpMethod method, String uri, Object body) {
        var request = http.method(method).uri(uri).header("X-Mystix-Company-Id", company.toString());
        if (body != null) {
            request = request.contentType(MediaType.APPLICATION_JSON).body(body);
        }
        return request.retrieve().toEntity(Map.class);
    }

    private Map get(String uri) {
        return http.get().uri(uri).header("X-Mystix-Company-Id", company.toString()).retrieve().body(Map.class);
    }

    private Map waitFor(String uri, Predicate<Map> done) {
        Map last = null;
        for (int i = 0; i < 100; i++) {
            last = get(uri);
            if (done.test(last)) {
                return last;
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        throw new AssertionError(uri + " did not reach the expected state: " + last);
    }
}
