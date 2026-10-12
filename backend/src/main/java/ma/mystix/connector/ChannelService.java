package ma.mystix.connector;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

import ma.mystix.partner.PartnerService;
import ma.mystix.shared.error.ApiError;
import ma.mystix.shared.error.ErrorCode;
import ma.mystix.shared.error.MystixException;
import ma.mystix.tenant.CompanyService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * Communication channels of each client environment (ADR-0015), configured from Mystix or from Atelys: one table, one
 * service, so both applications see and change the same profile. Every query is scoped by {@code company_id}.
 * <p>
 * This stores and checks the configuration; the transports themselves (AS2, SFTP, FTP/FTPS) run in Lot 6.
 */
@Service
public class ChannelService {

    private static final Logger LOG = LoggerFactory.getLogger(ChannelService.class);

    /** An alias in the secret store, never the secret itself. */
    private static final Pattern CREDENTIAL_REF = Pattern.compile("^[A-Za-z0-9._:/-]{1,120}$");

    public enum App { MYSTIX, ATELYS }

    public record Config(String name, Channel.Protocol protocol, Channel.Direction direction, UUID partnerId,
                         boolean usedByMystix, boolean usedByAtelys, Channel.Status status,
                         Map<String, Object> settings, String credentialRef) {
    }

    private final JdbcClient jdbc;
    private final CompanyService companies;
    private final PartnerService partners;
    private final JsonMapper json;
    private final Clock clock;
    private final RowMapper<Channel> rows;

    ChannelService(JdbcClient jdbc, CompanyService companies, PartnerService partners, JsonMapper json, Clock clock) {
        this.jdbc = jdbc;
        this.companies = companies;
        this.partners = partners;
        this.json = json;
        this.clock = clock;
        this.rows = (rs, n) -> new Channel(
                rs.getObject("id", UUID.class),
                rs.getObject("company_id", UUID.class),
                rs.getString("name"),
                Channel.Protocol.valueOf(rs.getString("protocol")),
                Channel.Direction.valueOf(rs.getString("direction")),
                rs.getObject("partner_id", UUID.class),
                rs.getBoolean("used_by_mystix"),
                rs.getBoolean("used_by_atelys"),
                Channel.Status.valueOf(rs.getString("status")),
                json.readValue(rs.getString("settings"), new TypeReference<Map<String, Object>>() { }),
                rs.getString("credential_ref"),
                rs.getString("created_by"),
                rs.getObject("created_at", OffsetDateTime.class),
                rs.getObject("updated_at", OffsetDateTime.class));
    }

    /** Channels of a company; with {@code app}, only those that application uses. */
    @Transactional(readOnly = true)
    public List<Channel> list(UUID companyId, App app) {
        companies.get(companyId);
        String filter = app == null ? "" : app == App.MYSTIX ? " AND used_by_mystix" : " AND used_by_atelys";
        return jdbc.sql("SELECT * FROM comm_channel WHERE company_id = :c" + filter + " ORDER BY protocol, name")
                .param("c", companyId).query(rows).list();
    }

    @Transactional(readOnly = true)
    public Channel get(UUID companyId, UUID id) {
        return jdbc.sql("SELECT * FROM comm_channel WHERE company_id = :c AND id = :id")
                .param("c", companyId).param("id", id).query(rows).optional()
                .orElseThrow(() -> new MystixException(ErrorCode.CHANNEL_NOT_FOUND,
                        "No channel " + id + " for company " + companyId));
    }

    @Transactional
    public Channel create(UUID companyId, Config config, String actor) {
        companies.get(companyId);
        Checked c = check(companyId, config);
        UUID id = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now(clock);
        try {
            jdbc.sql("""
                            INSERT INTO comm_channel (id, company_id, name, protocol, direction, partner_id,
                                                      used_by_mystix, used_by_atelys, status, settings,
                                                      credential_ref, created_by, created_at, updated_at)
                            VALUES (:id, :c, :name, :protocol, :direction, :partner, :mystix, :atelys, :status,
                                    CAST(:settings AS jsonb), :cred, :actor, :now, :now)
                            """)
                    .param("id", id).param("c", companyId).params(c.params()).param("actor", actor)
                    .param("now", now).update();
        } catch (DuplicateKeyException e) {
            throw new MystixException(ErrorCode.CHANNEL_NAME_TAKEN, "Channel name taken: " + config.name(), e);
        }
        LOG.info("Channel {} ({}) created for company {} by {}", id, config.protocol(), companyId, actor);
        return get(companyId, id);
    }

    @Transactional
    public Channel update(UUID companyId, UUID id, Config config, String actor) {
        get(companyId, id);
        Checked c = check(companyId, config);
        try {
            jdbc.sql("""
                            UPDATE comm_channel
                            SET name = :name, protocol = :protocol, direction = :direction, partner_id = :partner,
                                used_by_mystix = :mystix, used_by_atelys = :atelys, status = :status,
                                settings = CAST(:settings AS jsonb), credential_ref = :cred, updated_at = :now
                            WHERE company_id = :c AND id = :id
                            """)
                    .param("id", id).param("c", companyId).params(c.params())
                    .param("now", OffsetDateTime.now(clock)).update();
        } catch (DuplicateKeyException e) {
            throw new MystixException(ErrorCode.CHANNEL_NAME_TAKEN, "Channel name taken: " + config.name(), e);
        }
        LOG.info("Channel {} updated for company {} by {}", id, companyId, actor);
        return get(companyId, id);
    }

    @Transactional
    public void delete(UUID companyId, UUID id, String actor) {
        get(companyId, id);
        jdbc.sql("DELETE FROM comm_channel WHERE company_id = :c AND id = :id")
                .param("c", companyId).param("id", id).update();
        LOG.info("Channel {} deleted for company {} by {}", id, companyId, actor);
    }

    private record Checked(Map<String, Object> params) {
    }

    private Checked check(UUID companyId, Config config) {
        List<ApiError.FieldViolation> violations = new ArrayList<>();
        String name = config.name() == null ? "" : config.name().strip();
        if (name.isEmpty() || name.length() > 120) {
            violations.add(new ApiError.FieldViolation("name", "1 to 120 characters"));
        }
        if (config.protocol() == null) {
            violations.add(new ApiError.FieldViolation("protocol", "required"));
        }
        if (config.direction() == null) {
            violations.add(new ApiError.FieldViolation("direction", "required"));
        }
        if (!config.usedByMystix() && !config.usedByAtelys()) {
            violations.add(new ApiError.FieldViolation("usedByMystix", "the channel must be used by Mystix, Atelys or both"));
        }
        String credential = config.credentialRef() == null || config.credentialRef().isBlank()
                ? null : config.credentialRef().strip();
        if (credential != null && !CREDENTIAL_REF.matcher(credential).matches()) {
            violations.add(new ApiError.FieldViolation("credentialRef",
                    "an alias of the secret store (letters, digits, . _ : / -), never the secret itself"));
        }
        Map<String, Object> settings = Map.of();
        if (config.protocol() != null && config.direction() != null) {
            ChannelSettings.Result result = ChannelSettings.check(config.protocol(), config.direction(),
                    config.settings());
            violations.addAll(result.violations());
            settings = result.settings();
        }
        if (!violations.isEmpty()) {
            throw new MystixException(ErrorCode.VALIDATION_FAILED, "Invalid channel", violations, List.of(), null);
        }
        if (config.partnerId() != null) {
            partners.get(companyId, config.partnerId());
        }
        Channel.Status status = config.status() == null ? Channel.Status.DRAFT : config.status();
        if (status == Channel.Status.ACTIVE && credential == null && needsCredential(config.protocol(), settings)) {
            throw new MystixException(ErrorCode.CHANNEL_INCOMPLETE,
                    config.protocol() + " channel needs a credentialRef to be active");
        }
        Map<String, Object> params = new HashMap<>();
        params.put("name", name);
        params.put("protocol", config.protocol().name());
        params.put("direction", config.direction().name());
        params.put("partner", config.partnerId());
        params.put("mystix", config.usedByMystix());
        params.put("atelys", config.usedByAtelys());
        params.put("status", status.name());
        params.put("settings", json.writeValueAsString(settings));
        params.put("cred", credential);
        return new Checked(params);
    }

    private static boolean needsCredential(Channel.Protocol protocol, Map<String, Object> settings) {
        return switch (protocol) {
            case SFTP, FTP, FTPS, AS2 -> true;
            case HTTPS -> !"NONE".equals(settings.get("authType"));
            case WEBHOOK -> false;
        };
    }
}
