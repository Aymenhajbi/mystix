package ma.mystix.connector;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import com.helger.as2lib.message.AS2Message;
import com.helger.as2lib.message.AS2MessageMDN;
import com.helger.as2lib.processor.CNetAttribute;
import com.helger.as2lib.util.http.IAS2HttpResponseHandler;
import java.io.ByteArrayInputStream;

import com.helger.as2lib.util.http.HTTPHelper;
import com.helger.as2lib.util.http.IAS2HttpRequestDataProvider;
import com.helger.commons.http.HttpHeaderMap;
import jakarta.activation.DataSource;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import ma.mystix.shared.error.ErrorCode;
import ma.mystix.shared.error.MystixException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Receives AS2 messages on started AS2 listeners (ADR-0004, ADR-0016): {@code POST /inbound/as2/{listenerId}}.
 * as2-lib decrypts, verifies and answers with the MDN; this controller adds what the library does not keep: the
 * size limit, the journal, and duplicates (same Message-ID on the channel: the MDN given the first time is given
 * again, byte for byte, and nothing is processed twice).
 */
@RestController
class As2InboundController {

    static final String PATH = "/inbound/as2";
    private static final Logger LOG = LoggerFactory.getLogger(As2InboundController.class);

    private final As2ServerAdapter adapter;
    private final As2MessageStore messages;
    private final ListenerStore store;
    private final EntryPointDispatcher dispatcher;

    As2InboundController(As2ServerAdapter adapter, As2MessageStore messages, ListenerStore store,
                         EntryPointDispatcher dispatcher) {
        this.adapter = adapter;
        this.messages = messages;
        this.store = store;
        this.dispatcher = dispatcher;
    }

    @PostMapping(PATH + "/{listenerId}")
    void receive(@PathVariable UUID listenerId, HttpServletRequest request, HttpServletResponse response)
            throws IOException {
        As2ServerAdapter.Endpoint endpoint = adapter.endpoint(listenerId).orElseThrow(() -> new MystixException(
                ErrorCode.LISTENER_NOT_RUNNING, "AS2 listener " + listenerId + " is not running here"));
        UUID correlation = UUID.randomUUID();
        String messageId = request.getHeader("Message-ID");
        String from = unquote(request.getHeader("AS2-From"));
        String to = unquote(request.getHeader("AS2-To"));

        if (request.getContentLengthLong() > endpoint.maxBytes()) {
            dispatcher.refuse(endpoint.companyId(), listenerId, correlation, "INBOUND_TOO_LARGE",
                    request.getContentLengthLong() + " bytes announced", Map.of("transport", "AS2"));
            throw new MystixException(ErrorCode.INBOUND_TOO_LARGE, "AS2 message too large");
        }
        byte[] body;
        try (InputStream in = request.getInputStream()) {
            body = in.readNBytes(endpoint.maxBytes() + 1);
        }
        if (body.length > endpoint.maxBytes()) {
            dispatcher.refuse(endpoint.companyId(), listenerId, correlation, "INBOUND_TOO_LARGE",
                    "more than " + endpoint.maxBytes() + " bytes", Map.of("transport", "AS2"));
            throw new MystixException(ErrorCode.INBOUND_TOO_LARGE, "AS2 message too large");
        }

        // A message seen before on this channel: the same MDN, nothing processed again.
        if (messageId != null) {
            var previous = messages.mdnOf(endpoint.channelId(), messageId);
            if (previous.isPresent()) {
                store.event(endpoint.companyId(), listenerId, "MESSAGE_DUPLICATE", "INFO", correlation,
                        Map.of("transport", "AS2", "messageId", messageId), null);
                write(response, previous.get());
                return;
            }
        }

        AS2Message message = new AS2Message();
        HttpHeaderMap headers = new HttpHeaderMap();
        for (String name : Collections.list(request.getHeaderNames())) {
            for (String value : Collections.list(request.getHeaders(name))) {
                headers.addHeader(name, value);
            }
        }
        message.headers().setAllHeaders(headers);
        message.attrs().putIn(CNetAttribute.MA_SOURCE_IP, request.getRemoteAddr());
        message.attrs().putIn(CNetAttribute.MA_SOURCE_PORT, request.getRemotePort());
        message.attrs().putIn(CNetAttribute.MA_DESTINATION_IP, request.getLocalAddr());
        message.attrs().putIn(CNetAttribute.MA_DESTINATION_PORT, request.getLocalPort());
        message.attrs().putIn(HTTPHelper.MA_HTTP_REQ_TYPE, request.getMethod());
        message.attrs().putIn(HTTPHelper.MA_HTTP_REQ_URL, request.getRequestURI());
        message.attrs().putIn(As2ServerAdapter.ATTR_CORRELATION, correlation.toString());

        // Same reading and decoding as as2-lib's own servlet (content type, transfer encoding), from the bytes read.
        Captured captured = new Captured();
        DataSource data;
        try {
            data = HTTPHelper.readAndDecodeHttpRequest(new IAS2HttpRequestDataProvider() {
                @Override
                public InputStream getHttpInputStream() {
                    return new ByteArrayInputStream(body);
                }

                @Override
                public boolean isChunkedEncodingAlreadyProcessed() {
                    return true;
                }

                @Override
                public String getHttpRequestMethod() {
                    return request.getMethod();
                }

                @Override
                public String getHttpRequestUrl() {
                    return request.getRequestURI();
                }

                @Override
                public String getHttpRequestVersion() {
                    return request.getProtocol();
                }

                @Override
                public HttpHeaderMap getHttpHeaderMap() {
                    return headers;
                }
            }, captured, message, null);
        } catch (jakarta.mail.MessagingException e) {
            throw new MystixException(ErrorCode.VALIDATION_FAILED, "AS2 request unreadable: " + e.getMessage(), e);
        }
        endpoint.handler().handleIncomingMessage(request.getRemoteAddr() + ":" + request.getRemotePort(), data,
                message, captured);
        As2MessageStore.StoredMdn mdn = captured.toMdn();

        String disposition = message.getMDN() == null ? null
                : message.getMDN().attrs().getAsString(AS2MessageMDN.MDNA_DISPOSITION);
        String invoiceId = message.attrs().getAsString(As2ServerAdapter.ATTR_INVOICE);
        String error = message.attrs().getAsString(As2ServerAdapter.ATTR_ERROR);
        boolean processed = disposition != null && disposition.endsWith("processed");
        if (!processed) {
            // Refused at the transport level (signature, decryption, partner): the pipeline never saw it.
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("transport", "AS2");
            details.put("disposition", String.valueOf(disposition));
            if (messageId != null) {
                details.put("messageId", messageId);
            }
            store.received(listenerId, false);
            store.event(endpoint.companyId(), listenerId, "MESSAGE_REFUSED", "WARN", correlation, details, null);
        }
        if (messageId != null) {
            messages.record(endpoint.companyId(), endpoint.channelId(), listenerId, "IN", messageId,
                    from == null ? "" : from, to == null ? "" : to,
                    message.attrs().getAsString(As2ServerAdapter.ATTR_SHA256),
                    message.attrs().containsKey(As2ServerAdapter.ATTR_SIZE)
                            ? Long.valueOf(message.attrs().getAsString(As2ServerAdapter.ATTR_SIZE)) : null,
                    message.getMDN() == null ? null : message.getMDN().attrs().getAsString(AS2MessageMDN.MDNA_MIC),
                    disposition, null, null, mdn, correlation, invoiceId == null ? null : UUID.fromString(invoiceId),
                    error, null);
        }
        LOG.info("AS2 message {} on listener {}: {}", messageId, listenerId, disposition);
        write(response, mdn);
    }

    private static void write(HttpServletResponse response, As2MessageStore.StoredMdn mdn) throws IOException {
        response.setStatus(mdn.status());
        mdn.headers().forEach(response::setHeader);
        if (mdn.body() != null && mdn.body().length > 0) {
            response.getOutputStream().write(mdn.body());
        }
        response.flushBuffer();
    }

    private static String unquote(String value) {
        if (value == null) {
            return null;
        }
        String v = value.strip();
        return v.length() >= 2 && v.startsWith("\"") && v.endsWith("\"") ? v.substring(1, v.length() - 1) : v;
    }

    /** The HTTP answer as2-lib writes, kept to be sent now and again on a duplicate. */
    private static final class Captured implements IAS2HttpResponseHandler {

        private int status = 500;
        private final Map<String, String> headers = new LinkedHashMap<>();
        private byte[] body = new byte[0];

        @Override
        public void sendHttpResponse(int code, com.helger.commons.http.HttpHeaderMap headerMap,
                                     com.helger.commons.io.IWriteToStream data) throws IOException {
            status = code;
            headerMap.forEachSingleHeader((name, value) -> {
                // The servlet container sets these itself.
                if (!name.equalsIgnoreCase("Content-Length") && !name.equalsIgnoreCase("Transfer-Encoding")
                        && !name.equalsIgnoreCase("Connection")) {
                    headers.merge(name, value, (a, b) -> a + ", " + b);
                }
            }, false);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            if (data != null) {
                data.writeTo(out);
            }
            body = out.toByteArray();
        }

        As2MessageStore.StoredMdn toMdn() {
            return new As2MessageStore.StoredMdn(status, headers, body);
        }
    }
}
