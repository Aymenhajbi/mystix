package ma.mystix.connector;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import com.helger.as2lib.cert.PredefinedCertificateFactory;
import com.helger.as2lib.exception.AS2Exception;
import com.helger.as2lib.message.IMessage;
import com.helger.as2lib.partner.AbstractPartnershipFactory;
import com.helger.as2lib.partner.Partnership;
import com.helger.as2lib.processor.DefaultMessageProcessor;
import com.helger.as2lib.processor.module.AbstractProcessorModule;
import com.helger.as2lib.processor.receiver.AS2ReceiverModule;
import com.helger.as2lib.processor.receiver.net.AS2ReceiverHandler;
import com.helger.as2lib.processor.storage.IProcessorStorageModule;
import com.helger.as2lib.session.AS2Session;
import com.helger.commons.collection.attr.StringMap;
import ma.mystix.flow.ExchangeFlow;
import ma.mystix.shared.error.ApiError;
import ma.mystix.shared.error.MystixException;
import org.springframework.stereotype.Component;

/**
 * AS2 receiving listener (ADR-0004, ADR-0016): {@code POST /inbound/as2/{listenerId}} on the Mystix server. Started,
 * the listener holds an as2-lib session built from its channel: Mystix's key and certificate, the partner's
 * certificate, one partnership (partner AS2-From to Mystix AS2-To). Decryption, signature check, MIC and MDN are
 * as2-lib's; the decrypted payload is handed to the entry point.
 * <p>
 * The MDN reports the transport result: {@code processed} once the payload is decrypted, verified and handed to the
 * pipeline, whatever the pipeline then decides about the document (journaled apart, never hidden).
 */
@Component
class As2ServerAdapter implements ListenerAdapter {

    static final String ATTR_CORRELATION = "mystix.correlation";
    static final String ATTR_INVOICE = "mystix.invoice";
    static final String ATTR_ERROR = "mystix.error";
    static final String ATTR_SHA256 = "mystix.sha256";
    static final String ATTR_SIZE = "mystix.size";

    /** A started AS2 receiver: what the controller needs to accept a message on it. */
    record Endpoint(UUID listenerId, UUID companyId, UUID channelId, UUID flowId, String localId, String partnerId,
                    int maxBytes, AS2ReceiverModule module) {

        AS2ReceiverHandler handler() {
            return module.createHandler();
        }
    }

    private final As2Keys keys;
    private final EntryPointDispatcher dispatcher;
    private final Map<UUID, Endpoint> endpoints = new ConcurrentHashMap<>();

    As2ServerAdapter(As2Keys keys, EntryPointDispatcher dispatcher) {
        this.keys = keys;
        this.dispatcher = dispatcher;
    }

    @Override
    public Listener.Kind kind() {
        return Listener.Kind.AS2_SERVER;
    }

    @Override
    public List<ApiError.FieldViolation> preconditions(Context c) {
        List<ApiError.FieldViolation> problems = new ArrayList<>();
        Channel channel = c.channel();
        if (channel.protocol() != Channel.Protocol.AS2) {
            problems.add(new ApiError.FieldViolation("channel.protocol", "AS2 required"));
        }
        if (channel.direction() == Channel.Direction.OUT) {
            problems.add(new ApiError.FieldViolation("channel.direction", "IN or BOTH required to receive"));
        }
        if (channel.status() != Channel.Status.ACTIVE) {
            problems.add(new ApiError.FieldViolation("channel.status", "the channel must be ACTIVE"));
        }
        if (channel.protocol() == Channel.Protocol.AS2) {
            for (String missing : keys.missing(channel)) {
                problems.add(new ApiError.FieldViolation(missing, switch (missing) {
                    case "secret" -> "Mystix's private key (secret " + channel.credentialRef() + ") is not in the store";
                    case "certificate.own" -> "Mystix's certificate (role OWN) is missing on the channel";
                    default -> "the partner's certificate (role PARTNER) is missing on the channel";
                }));
            }
        }
        EntryPoint entry = c.entryPoint();
        if (!entry.enabled()) {
            problems.add(new ApiError.FieldViolation("entryPoint.enabled", "the entry point is disabled"));
        }
        if (!entry.dispatchable()) {
            problems.add(new ApiError.FieldViolation("entryPoint.inputFormat", entry.documentType() + " in "
                    + entry.inputFormat() + " cannot be handed to the pipeline yet; JSON invoices can"));
        }
        if (c.flow().status() != ExchangeFlow.Status.ACTIVE) {
            problems.add(new ApiError.FieldViolation("entryPoint.flow", "the flow " + c.flow().name() + " is not ACTIVE"));
        }
        HttpInboundAdapter.maxBytes(c.listener().settings(), problems);
        return problems;
    }

    @Override
    public Handle start(Context c) {
        Channel channel = c.channel();
        As2Keys.Material material;
        try {
            material = keys.load(channel);
        } catch (RuntimeException e) {
            throw new StartFailure("SECRET_INVALID", e.getMessage(), e);
        }
        Listener listener = c.listener();
        try {
            AS2Session session = new AS2Session();
            // Verify signatures with the partner certificate registered on the channel, never with a certificate
            // the message carries itself: otherwise anyone could sign with their own key under the partner name.
            session.setCryptoVerifyUseCertificateInBodyPart(false);
            PredefinedCertificateFactory certificates = new PredefinedCertificateFactory(material.keyStore());
            StringMap certificateOptions = new StringMap();
            certificateOptions.putIn(PredefinedCertificateFactory.ATTR_TYPE, "PKCS12");
            certificateOptions.putIn(PredefinedCertificateFactory.ATTR_PASSWORD, new String(material.password()));
            certificates.initDynamicComponent(session, certificateOptions);
            session.setCertificateFactory(certificates);

            Partnership partnership = new Partnership("mystix-" + channel.id());
            partnership.setSenderAS2ID(material.partnerAlias());
            partnership.setSenderX509Alias(material.partnerAlias());
            partnership.setReceiverAS2ID(material.localAlias());
            partnership.setReceiverX509Alias(material.localAlias());
            // The algorithms agreed on the channel: as2-lib includes the MIME headers in the MIC when they are set,
            // as the sender does (RFC 4130 section 7.3.1).
            Map<String, Object> settings = channel.settings();
            var sign = As2Sender.sign(String.valueOf(settings.getOrDefault("signing", "SHA-256")));
            var crypt = As2Sender.crypt(String.valueOf(settings.getOrDefault("encryption", "AES256_CBC")));
            if (sign != null) {
                partnership.setSigningAlgorithm(sign);
            }
            if (crypt != null) {
                partnership.setEncryptAlgorithm(crypt);
            }
            AbstractPartnershipFactory partnerships = new AbstractPartnershipFactory() { };
            partnerships.initDynamicComponent(session, new StringMap());
            partnerships.addPartnership(partnership);
            session.setPartnershipFactory(partnerships);

            DefaultMessageProcessor processor = new DefaultMessageProcessor();
            processor.initDynamicComponent(session, new StringMap());
            processor.addModule(new Storage(listener.id(), listener.companyId(), c.flow().id(), dispatcher));
            session.setMessageProcessor(processor);

            AS2ReceiverModule module = new AS2ReceiverModule();
            StringMap options = new StringMap();
            // The module is driven by the HTTP controller; it never opens a socket of its own.
            options.putIn(AS2ReceiverModule.ATTR_PORT, "0");
            module.initDynamicComponent(session, options);

            Endpoint endpoint = new Endpoint(listener.id(), listener.companyId(), channel.id(), c.flow().id(),
                    material.localAlias(), material.partnerAlias(),
                    HttpInboundAdapter.maxBytes(listener.settings(), new ArrayList<>()), module);
            endpoints.put(listener.id(), endpoint);
            return new Handle(kind(), listener.id(), listener.companyId(), endpoint);
        } catch (AS2Exception e) {
            throw new StartFailure("AS2_CONFIGURATION", e.getMessage(), e);
        }
    }

    @Override
    public void stop(Handle handle) {
        endpoints.remove(handle.listenerId());
    }

    @Override
    public Health health(Handle handle) {
        Endpoint endpoint = endpoints.get(handle.listenerId());
        return endpoint != null && endpoint == handle.resource() ? Health.ok()
                : new Health(false, "ROUTE_MISSING", "the AS2 route is not registered in this process");
    }

    Optional<Endpoint> endpoint(UUID listenerId) {
        return Optional.ofNullable(endpoints.get(listenerId));
    }

    /**
     * as2-lib's storage step: the decrypted, verified payload goes to the entry point. A business refusal is recorded
     * on the message attributes and does not turn the transport result into an error.
     */
    static final class Storage extends AbstractProcessorModule implements IProcessorStorageModule {

        private final UUID listenerId;
        private final UUID companyId;
        private final UUID flowId;
        private final EntryPointDispatcher dispatcher;

        Storage(UUID listenerId, UUID companyId, UUID flowId, EntryPointDispatcher dispatcher) {
            this.listenerId = listenerId;
            this.companyId = companyId;
            this.flowId = flowId;
            this.dispatcher = dispatcher;
        }

        @Override
        public boolean canHandle(String action, IMessage message, Map<String, Object> options) {
            return DO_STORE.equals(action);
        }

        @Override
        public void handle(String action, IMessage message, Map<String, Object> options) throws AS2Exception {
            byte[] payload;
            try {
                payload = message.getData().getInputStream().readAllBytes();
            } catch (IOException | jakarta.mail.MessagingException e) {
                throw new AS2Exception("payload unreadable", e);
            }
            UUID correlation = UUID.fromString(message.attrs().getAsString(ATTR_CORRELATION));
            message.attrs().putIn(ATTR_SHA256, ma.mystix.shared.Sha256.hex(payload));
            message.attrs().putIn(ATTR_SIZE, payload.length);
            Map<String, Object> source = new java.util.LinkedHashMap<>();
            source.put("transport", "AS2");
            source.put("messageId", message.getMessageID());
            source.put("as2From", message.headers().getFirstHeaderValue("AS2-From"));
            try {
                var submission = dispatcher.dispatch(companyId, listenerId, flowId, payload, correlation, source);
                message.attrs().putIn(ATTR_INVOICE, submission.invoice().id().toString());
            } catch (MystixException e) {
                message.attrs().putIn(ATTR_ERROR, e.errorCode().name());
            }
        }
    }
}
