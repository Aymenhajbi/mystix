package ma.mystix.connector;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import ma.mystix.invoice.InvoiceReception;
import ma.mystix.invoice.InvoiceService;
import ma.mystix.shared.Sha256;
import ma.mystix.shared.error.ErrorCode;
import ma.mystix.shared.error.MystixException;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Receives on started HTTP inbound listeners (ADR-0016): {@code POST /inbound/http/{listenerId}}. Outside
 * {@code /api/v1}: the sender is a partner or an ERP with the listener's API key, not a portal session.
 * <p>
 * Reception failures (listener not running, key, size, content type) are told apart from business refusals of the
 * document; both are counted and journaled with the correlation id returned in {@value #CORRELATION_HEADER}.
 */
@RestController
class HttpInboundController {

    static final String PATH = "/inbound/http";
    static final String KEY_HEADER = "X-Mystix-Api-Key";
    static final String CORRELATION_HEADER = "X-Mystix-Correlation-Id";

    private final HttpInboundAdapter adapter;
    private final ListenerStore store;
    private final InvoiceReception invoices;

    HttpInboundController(HttpInboundAdapter adapter, ListenerStore store, InvoiceReception invoices) {
        this.adapter = adapter;
        this.store = store;
        this.invoices = invoices;
    }

    record Receipt(UUID correlationId, UUID invoiceId, String number, String status, boolean replayed) {
    }

    @PostMapping(value = PATH + "/{listenerId}", produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<Receipt> receive(@PathVariable UUID listenerId, HttpServletRequest request,
                                    HttpServletResponse response) throws IOException {
        UUID correlation = UUID.randomUUID();
        response.setHeader(CORRELATION_HEADER, correlation.toString());
        HttpInboundAdapter.Route route = adapter.route(listenerId).orElseThrow(() -> new MystixException(
                ErrorCode.LISTENER_NOT_RUNNING, "Listener " + listenerId + " is not running here"));

        String key = request.getHeader(KEY_HEADER);
        if (key == null || !sameHash(key, route.apiKeyHash())) {
            throw refused(route, correlation, ErrorCode.INBOUND_AUTH_FAILED,
                    key == null ? "no " + KEY_HEADER + " header" : "API key does not match");
        }
        String contentType = request.getContentType();
        if (contentType == null || !MediaType.APPLICATION_JSON.includes(MediaType.parseMediaType(contentType))) {
            throw refused(route, correlation, ErrorCode.INBOUND_UNSUPPORTED_TYPE, "content type " + contentType);
        }
        if (request.getContentLengthLong() > route.maxBytes()) {
            throw refused(route, correlation, ErrorCode.INBOUND_TOO_LARGE,
                    request.getContentLengthLong() + " bytes announced, limit " + route.maxBytes());
        }
        byte[] body;
        try (InputStream in = request.getInputStream()) {
            body = in.readNBytes(route.maxBytes() + 1);
        }
        if (body.length > route.maxBytes()) {
            throw refused(route, correlation, ErrorCode.INBOUND_TOO_LARGE, "more than " + route.maxBytes() + " bytes");
        }

        InvoiceService.Submission submission;
        try {
            submission = invoices.receive(route.companyId(), route.flowId(), body, true);
        } catch (MystixException e) {
            // A business refusal: the message reached the pipeline, which said no (already in the company logs).
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("errorCode", e.errorCode().name());
            details.put("stage", "PIPELINE");
            details.put("bytes", body.length);
            store.received(route.listenerId(), false);
            store.event(route.companyId(), route.listenerId(), "MESSAGE_REJECTED", "WARN", correlation, details, null);
            throw e;
        }
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("invoiceId", submission.invoice().id().toString());
        details.put("number", submission.invoice().number());
        details.put("bytes", body.length);
        details.put("sha256", Sha256.hex(body));
        details.put("replayed", submission.replayed());
        store.received(route.listenerId(), true);
        store.event(route.companyId(), route.listenerId(), "MESSAGE_RECEIVED", "INFO", correlation, details, null);

        Receipt receipt = new Receipt(correlation, submission.invoice().id(), submission.invoice().number(),
                submission.invoice().status(), submission.replayed());
        return submission.replayed()
                ? ResponseEntity.ok(receipt)
                : ResponseEntity.accepted().location(URI.create("/api/v1/invoices/" + submission.invoice().id()))
                        .body(receipt);
    }

    private MystixException refused(HttpInboundAdapter.Route route, UUID correlation, ErrorCode code, String reason) {
        store.received(route.listenerId(), false);
        store.event(route.companyId(), route.listenerId(), "MESSAGE_REFUSED", "WARN", correlation,
                Map.of("errorCode", code.name(), "stage", "RECEPTION", "reason", reason), null);
        return new MystixException(code, "Listener " + route.listenerId() + ": " + reason);
    }

    /** Constant-time comparison of the SHA-256 of the presented key with the stored one. */
    private static boolean sameHash(String key, String expectedHex) {
        byte[] presented = HexFormat.of().parseHex(Sha256.hex(key.getBytes(StandardCharsets.US_ASCII)));
        return MessageDigest.isEqual(presented, HexFormat.of().parseHex(expectedHex));
    }
}
