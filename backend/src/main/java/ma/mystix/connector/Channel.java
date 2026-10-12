package ma.mystix.connector;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

/**
 * A communication channel of a client environment (ADR-0015): one connection profile (AS2, SFTP, FTP, FTPS, HTTPS,
 * webhook), shared by Mystix and Atelys. {@code settings} holds only non-secret parameters, checked per protocol;
 * {@code credentialRef} names the entry of the secret store that holds the password, private key or certificate.
 *
 * @param partnerId the trading partner at the other end, optional
 */
public record Channel(UUID id, UUID companyId, String name, Protocol protocol, Direction direction, UUID partnerId,
                      boolean usedByMystix, boolean usedByAtelys, Status status, Map<String, Object> settings,
                      String credentialRef, String createdBy, OffsetDateTime createdAt, OffsetDateTime updatedAt) {

    public enum Protocol { AS2, SFTP, FTP, FTPS, HTTPS, WEBHOOK }

    /** IN: the client receives on this channel. OUT: the client sends. BOTH: one profile for both. */
    public enum Direction { IN, OUT, BOTH }

    /** DRAFT while being configured, ACTIVE when flows may use it, DISABLED to stop it without losing it. */
    public enum Status { DRAFT, ACTIVE, DISABLED }
}
