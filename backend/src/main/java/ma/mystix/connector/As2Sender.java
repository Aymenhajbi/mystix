package ma.mystix.connector;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

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
import com.helger.mail.cte.EContentTransferEncoding;
import com.helger.security.keystore.EKeyStoreType;
import ma.mystix.shared.Sha256;
import ma.mystix.shared.error.ApiError;
import ma.mystix.shared.error.ErrorCode;
import ma.mystix.shared.error.MystixException;
import org.apache.hc.core5.util.Timeout;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Sends a document to a partner over AS2 (ADR-0004): signed and encrypted as the channel says, synchronous MDN
 * requested, verified with the partner's certificate, and the MIC it returns compared with the one Mystix computed.
 * The exchange is recorded whatever happens (as2_message, direction OUT).
 */
@Service
public class As2Sender {

    private static final Logger LOG = LoggerFactory.getLogger(As2Sender.class);

    /** What the partner answered. {@code delivered} is true only for a signed, MIC-matching "processed" MDN. */
    public record Result(String messageId, boolean delivered, String disposition, boolean mdnSigned,
                         Boolean micMatched, long durationMs, String error, UUID correlationId) {
    }

    private final ChannelService channels;
    private final As2Keys keys;
    private final As2MessageStore messages;

    As2Sender(ChannelService channels, As2Keys keys, As2MessageStore messages) {
        this.channels = channels;
        this.keys = keys;
        this.messages = messages;
    }

    /** What prevents sending on this channel; empty when it can send. */
    List<ApiError.FieldViolation> preconditions(Channel channel) {
        List<ApiError.FieldViolation> problems = new ArrayList<>();
        Map<String, Object> s = channel.settings();
        if (channel.protocol() != Channel.Protocol.AS2) {
            problems.add(new ApiError.FieldViolation("channel.protocol", "AS2 required"));
            return problems;
        }
        if (channel.direction() == Channel.Direction.IN) {
            problems.add(new ApiError.FieldViolation("channel.direction", "OUT or BOTH required to send"));
        }
        if (channel.status() != Channel.Status.ACTIVE) {
            problems.add(new ApiError.FieldViolation("channel.status", "the channel must be ACTIVE"));
        }
        if (s.get("url") == null) {
            problems.add(new ApiError.FieldViolation("channel.url", "the partner AS2 URL is required to send"));
        }
        if ("ASYNC".equals(s.get("mdn"))) {
            problems.add(new ApiError.FieldViolation("channel.mdn", "asynchronous MDN is not supported yet; use SYNC"));
        }
        for (String missing : keys.missing(channel)) {
            problems.add(new ApiError.FieldViolation(missing, "required to sign and encrypt"));
        }
        return problems;
    }

    public Result send(UUID companyId, UUID channelId, byte[] payload, String contentType, String filename,
                       String subject) {
        Channel channel = channels.get(companyId, channelId);
        List<ApiError.FieldViolation> problems = preconditions(channel);
        if (!problems.isEmpty()) {
            throw new MystixException(ErrorCode.AS2_NOT_READY, "Channel " + channelId + " cannot send AS2", problems,
                    List.of(), null);
        }
        As2Keys.Material material = keys.load(channel);
        Map<String, Object> s = channel.settings();
        ECryptoAlgorithmSign sign = sign(String.valueOf(s.getOrDefault("signing", "SHA-256")));
        ECryptoAlgorithmCrypt crypt = crypt(String.valueOf(s.getOrDefault("encryption", "AES256_CBC")));
        boolean mdnRequested = !"NONE".equals(s.getOrDefault("mdn", "SYNC"));
        boolean mdnSigned = !Boolean.FALSE.equals(s.getOrDefault("mdnSigned", true));

        AtomicReference<Boolean> micMatched = new AtomicReference<>();
        AS2ClientSettings settings = new AS2ClientSettings();
        settings.setKeyStore(EKeyStoreType.PKCS12, material.pkcs12(), new String(material.password()));
        settings.setSenderData(material.localAlias(), "as2@mystix.invalid", material.localAlias());
        settings.setReceiverData(material.partnerAlias(), material.partnerAlias(), String.valueOf(s.get("url")));
        settings.setReceiverCertificate(material.partner());
        settings.setPartnershipName("mystix-" + channel.id());
        settings.setEncryptAndSign(crypt, sign);
        settings.setMDNRequested(mdnRequested);
        if (mdnRequested && mdnSigned && sign != null) {
            settings.setMDNOptions(new DispositionOptions()
                    .setProtocolImportance(DispositionOptions.IMPORTANCE_REQUIRED)
                    .setProtocol(DispositionOptions.PROTOCOL_PKCS7_SIGNATURE)
                    .setMICAlgImportance(DispositionOptions.IMPORTANCE_REQUIRED)
                    .setMICAlg(sign));
        }
        settings.setRetryCount(0);
        settings.setConnectTimeout(Timeout.ofSeconds(10));
        settings.setResponseTimeout(Timeout.ofSeconds(60));
        settings.setMICMatchingHandler(new IMICMatchingHandler() {
            @Override
            public void onMICMatch(IMessage message, String mic) {
                micMatched.set(true);
            }

            @Override
            public void onMICMismatch(IMessage message, String original, String received) {
                micMatched.set(false);
                LOG.warn("AS2 MIC mismatch on {}: sent {}, MDN says {}", message.getMessageID(), original, received);
            }
        });

        AS2ClientRequest request = new AS2ClientRequest(subject).setData(payload).setContentType(contentType)
                .setFilename(filename)
                .setContentTransferEncoding("BASE64".equals(s.get("transferEncoding"))
                        ? EContentTransferEncoding.BASE64 : EContentTransferEncoding.BINARY);
        UUID correlation = UUID.randomUUID();
        AS2ClientResponse response = new AS2Client().sendSynchronous(settings, request);

        String messageId = response.getOriginalMessageID();
        String disposition = response.hasMDN()
                ? response.getMDN().attrs().getAsString(AS2MessageMDN.MDNA_DISPOSITION) : null;
        boolean signedMdn = response.getMDNVerificationCertificate() != null;
        long ms = response.hasExecutionDuration() ? response.getExecutionDuration().toMillis() : 0;
        String error = response.hasException() ? response.getException().getClass().getSimpleName() + ": "
                + response.getException().getMessage() : null;
        boolean processed = disposition != null && disposition.strip().endsWith("processed");
        boolean delivered = !response.hasException() && (!mdnRequested
                || (processed && (!mdnSigned || signedMdn) && !Boolean.FALSE.equals(micMatched.get())));

        messages.record(companyId, channelId, null, "OUT", messageId == null ? "<none:" + correlation + ">" : messageId,
                material.localAlias(), material.partnerAlias(), Sha256.hex(payload), (long) payload.length,
                response.hasMDN() ? response.getMDN().attrs().getAsString(AS2MessageMDN.MDNA_MIC) : null,
                disposition, signedMdn, micMatched.get(), null, correlation, null,
                delivered ? null : error != null ? "AS2_SEND_FAILED" : "AS2_MDN_NOT_PROCESSED", ms);
        LOG.info("AS2 message {} to {} on channel {}: delivered={}, disposition={}, MIC matched={}", messageId,
                material.partnerAlias(), channelId, delivered, disposition, micMatched.get());
        if (response.hasException() && !response.hasMDN()) {
            throw new MystixException(ErrorCode.AS2_SEND_FAILED, "AS2 send on channel " + channelId + " failed: "
                    + error, response.getException());
        }
        return new Result(messageId, delivered, disposition, signedMdn, micMatched.get(), ms, error, correlation);
    }

    static ECryptoAlgorithmSign sign(String value) {
        return switch (value) {
            case "NONE" -> null;
            case "SHA-1" -> ECryptoAlgorithmSign.DIGEST_SHA_1;
            case "SHA-384" -> ECryptoAlgorithmSign.DIGEST_SHA_384;
            case "SHA-512" -> ECryptoAlgorithmSign.DIGEST_SHA_512;
            default -> ECryptoAlgorithmSign.DIGEST_SHA_256;
        };
    }

    static ECryptoAlgorithmCrypt crypt(String value) {
        return switch (value) {
            case "NONE" -> null;
            case "3DES" -> ECryptoAlgorithmCrypt.CRYPT_3DES;
            case "AES128_CBC" -> ECryptoAlgorithmCrypt.CRYPT_AES128_CBC;
            case "AES192_CBC" -> ECryptoAlgorithmCrypt.CRYPT_AES192_CBC;
            default -> ECryptoAlgorithmCrypt.CRYPT_AES256_CBC;
        };
    }
}
