package ma.mystix.connector;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.PublicKey;
import java.security.cert.CertificateEncodingException;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.interfaces.ECPublicKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

import ma.mystix.shared.error.ErrorCode;
import ma.mystix.shared.error.MystixException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Public X.509 certificates of the communication channels (ADR-0015): ours, handed to the partner, and the partner's.
 * Anything that carries a private key is refused: private keys live in the secret store only.
 */
@Service
public class CertificateService {

    private static final Logger LOG = LoggerFactory.getLogger(CertificateService.class);
    private static final Pattern PEM_BLOCK = Pattern.compile(
            "-----BEGIN CERTIFICATE-----\\s*([A-Za-z0-9+/=\\s]+?)\\s*-----END CERTIFICATE-----");
    private static final int MAX_INPUT = 64 * 1024;

    public enum Role { OWN, PARTNER }

    public record Certificate(UUID id, UUID channelId, Role role, String subject, String issuer, String serialNumber,
                              String keyAlgorithm, Integer keySize, OffsetDateTime notBefore, OffsetDateTime notAfter,
                              String sha256Fingerprint, String pem, String createdBy, OffsetDateTime createdAt) {
    }

    private static final RowMapper<Certificate> ROWS = (rs, n) -> new Certificate(
            rs.getObject("id", UUID.class),
            rs.getObject("channel_id", UUID.class),
            Role.valueOf(rs.getString("role")),
            rs.getString("subject"),
            rs.getString("issuer"),
            rs.getString("serial_number"),
            rs.getString("key_algorithm"),
            (Integer) rs.getObject("key_size"),
            rs.getObject("not_before", OffsetDateTime.class),
            rs.getObject("not_after", OffsetDateTime.class),
            rs.getString("sha256_fingerprint"),
            rs.getString("pem"),
            rs.getString("created_by"),
            rs.getObject("created_at", OffsetDateTime.class));

    private final JdbcClient jdbc;
    private final ChannelService channels;
    private final Clock clock;

    CertificateService(JdbcClient jdbc, ChannelService channels, Clock clock) {
        this.jdbc = jdbc;
        this.channels = channels;
        this.clock = clock;
    }

    /** Every certificate of the company, soonest expiry first: for the expiry alerts of the channel list. */
    @Transactional(readOnly = true)
    public List<Certificate> all(UUID companyId) {
        return jdbc.sql("SELECT * FROM comm_certificate WHERE company_id = :c ORDER BY not_after")
                .param("c", companyId).query(ROWS).list();
    }

    @Transactional(readOnly = true)
    public List<Certificate> list(UUID companyId, UUID channelId) {
        channels.get(companyId, channelId);
        return jdbc.sql("""
                        SELECT * FROM comm_certificate WHERE company_id = :c AND channel_id = :ch
                        ORDER BY role, not_after DESC
                        """)
                .param("c", companyId).param("ch", channelId).query(ROWS).list();
    }

    /** Adds a certificate given as PEM text or base64 DER. */
    @Transactional
    public Certificate add(UUID companyId, UUID channelId, Role role, String input, String actor) {
        channels.get(companyId, channelId);
        if (role == null) {
            throw new MystixException(ErrorCode.CERTIFICATE_INVALID, "Certificate role missing");
        }
        X509Certificate x509 = parse(input);
        String pem = toPem(x509);
        PublicKey key = x509.getPublicKey();
        UUID id = UUID.randomUUID();
        try {
            jdbc.sql("""
                            INSERT INTO comm_certificate (id, company_id, channel_id, role, subject, issuer, serial_number,
                                                          key_algorithm, key_size, not_before, not_after,
                                                          sha256_fingerprint, pem, created_by, created_at)
                            VALUES (:id, :c, :ch, :role, :subject, :issuer, :serial, :alg, :size, :from, :to, :fp,
                                    :pem, :actor, :now)
                            """)
                    .param("id", id).param("c", companyId).param("ch", channelId).param("role", role.name())
                    .param("subject", truncate(x509.getSubjectX500Principal().getName(), 1000))
                    .param("issuer", truncate(x509.getIssuerX500Principal().getName(), 1000))
                    .param("serial", truncate(x509.getSerialNumber().toString(16).toUpperCase(), 80))
                    .param("alg", key.getAlgorithm()).param("size", keySize(key))
                    .param("from", OffsetDateTime.ofInstant(x509.getNotBefore().toInstant(), ZoneOffset.UTC))
                    .param("to", OffsetDateTime.ofInstant(x509.getNotAfter().toInstant(), ZoneOffset.UTC))
                    .param("fp", fingerprint(x509)).param("pem", pem).param("actor", actor)
                    .param("now", OffsetDateTime.now(clock)).update();
        } catch (DuplicateKeyException e) {
            throw new MystixException(ErrorCode.CERTIFICATE_DUPLICATE, "Certificate already on channel " + channelId, e);
        }
        LOG.info("Certificate {} ({}) added to channel {} by {}", id, role, channelId, actor);
        return get(companyId, channelId, id);
    }

    @Transactional(readOnly = true)
    public Certificate get(UUID companyId, UUID channelId, UUID id) {
        return jdbc.sql("SELECT * FROM comm_certificate WHERE company_id = :c AND channel_id = :ch AND id = :id")
                .param("c", companyId).param("ch", channelId).param("id", id).query(ROWS).optional()
                .orElseThrow(() -> new MystixException(ErrorCode.CERTIFICATE_NOT_FOUND,
                        "No certificate " + id + " on channel " + channelId));
    }

    @Transactional
    public void delete(UUID companyId, UUID channelId, UUID id, String actor) {
        get(companyId, channelId, id);
        jdbc.sql("DELETE FROM comm_certificate WHERE company_id = :c AND channel_id = :ch AND id = :id")
                .param("c", companyId).param("ch", channelId).param("id", id).update();
        LOG.info("Certificate {} removed from channel {} by {}", id, channelId, actor);
    }

    static X509Certificate parse(String input) {
        if (input == null || input.isBlank() || input.length() > MAX_INPUT) {
            throw new MystixException(ErrorCode.CERTIFICATE_INVALID, "Empty or oversized certificate");
        }
        // A private key must never reach Mystix: refuse the whole input rather than strip it.
        if (input.contains("PRIVATE KEY")) {
            throw new MystixException(ErrorCode.CERTIFICATE_PRIVATE_KEY_REFUSED, "Input carries a private key");
        }
        byte[] der;
        var matcher = PEM_BLOCK.matcher(input);
        try {
            if (matcher.find()) {
                der = Base64.getMimeDecoder().decode(matcher.group(1));
            } else {
                der = Base64.getMimeDecoder().decode(input.strip());
            }
            CertificateFactory factory = CertificateFactory.getInstance("X.509");
            return (X509Certificate) factory.generateCertificate(new ByteArrayInputStream(der));
        } catch (IllegalArgumentException | CertificateException | ClassCastException e) {
            throw new MystixException(ErrorCode.CERTIFICATE_INVALID, "Not an X.509 certificate", e);
        }
    }

    static String fingerprint(X509Certificate certificate) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(certificate.getEncoded());
            return HexFormat.ofDelimiter(":").withUpperCase().formatHex(digest);
        } catch (NoSuchAlgorithmException | CertificateEncodingException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String toPem(X509Certificate certificate) {
        try {
            String body = Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII))
                    .encodeToString(certificate.getEncoded());
            return "-----BEGIN CERTIFICATE-----\n" + body + "\n-----END CERTIFICATE-----\n";
        } catch (CertificateEncodingException e) {
            throw new MystixException(ErrorCode.CERTIFICATE_INVALID, "Certificate cannot be encoded", e);
        }
    }

    private static Integer keySize(PublicKey key) {
        if (key instanceof RSAPublicKey rsa) {
            return rsa.getModulus().bitLength();
        }
        if (key instanceof ECPublicKey ec) {
            return ec.getParams().getCurve().getField().getFieldSize();
        }
        return null;
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max);
    }
}
