package ma.mystix.device;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import ma.mystix.shared.error.ErrorCode;
import ma.mystix.shared.error.MystixException;
import ma.mystix.wms.AtelysAudit;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * Selection rules sent to the terminals (ADR-0019): data, never code. Each change raises the version; a retired
 * profile stays in the history and is no longer sent.
 */
@Service
public class DeviceProfiles {

    public record Profile(UUID id, String name, int version, String scope, String manufacturer, List<String> models,
                          String installationId, Integer minSdk, Integer maxSdk, String recommendedAdapter,
                          String prerequisites, String confidence, String status, String testProcedure,
                          OffsetDateTime updatedAt, String updatedBy) {
    }

    public record ProfileInput(String name, String scope, String manufacturer, List<String> models,
                               String installationId, Integer minSdk, Integer maxSdk, String recommendedAdapter,
                               String prerequisites, String confidence, String status, String testProcedure) {
    }

    private static final List<String> SCOPES = List.of("INSTALLATION", "MODEL", "MANUFACTURER");
    private static final List<String> CONFIDENCES = List.of("LOW", "MEDIUM", "HIGH");
    private static final List<String> EDITABLE_STATUSES = List.of("VALIDATED", "EXPERIMENTAL");

    private final JdbcClient jdbc;
    private final AtelysAudit audit;
    private final JsonMapper json;
    private final Clock clock;

    DeviceProfiles(JdbcClient jdbc, AtelysAudit audit, JsonMapper json, Clock clock) {
        this.jdbc = jdbc;
        this.audit = audit;
        this.json = json;
        this.clock = clock;
    }

    public List<Profile> list(UUID companyId) {
        return jdbc.sql("SELECT * FROM mobile_device_profile WHERE company_id = :c ORDER BY status, manufacturer, name")
                .param("c", companyId).query(this::profile).list();
    }

    /** The profiles a terminal receives: everything but retired ones. */
    public List<Profile> active(UUID companyId) {
        return list(companyId).stream().filter(p -> !"RETIRED".equals(p.status())).toList();
    }

    public Profile get(UUID companyId, UUID id) {
        return jdbc.sql("SELECT * FROM mobile_device_profile WHERE company_id = :c AND id = :id")
                .param("c", companyId).param("id", id).query(this::profile).optional()
                .orElseThrow(() -> new MystixException(ErrorCode.ATELYS_NOT_FOUND, "Not found: device profile " + id));
    }

    @Transactional
    public Profile create(UUID companyId, ProfileInput in, String actor) {
        ProfileInput p = validate(in);
        UUID id = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now(clock);
        jdbc.sql("""
                        INSERT INTO mobile_device_profile (id, company_id, name, version, scope, manufacturer, models,
                                                           installation_id, min_sdk, max_sdk, recommended_adapter,
                                                           prerequisites, confidence, status, test_procedure,
                                                           created_at, updated_at, updated_by)
                        VALUES (:id, :c, :name, 1, :scope, :man, CAST(:models AS jsonb), :inst, :min, :max, :adapter,
                                :pre, :conf, :status, :proc, :now, :now, :actor)
                        """)
                .param("id", id).param("c", companyId).params(fields(p)).param("now", now).param("actor", actor)
                .update();
        audit.record(companyId, "PROFILE_CREATED", "DEVICE_PROFILE", id, actor, null, summary(p));
        return get(companyId, id);
    }

    @Transactional
    public Profile update(UUID companyId, UUID id, ProfileInput in, String actor) {
        Profile before = get(companyId, id);
        if ("RETIRED".equals(before.status())) {
            throw new MystixException(ErrorCode.STATUS_TRANSITION_INVALID, "Profile " + id + " is retired");
        }
        ProfileInput p = validate(in);
        jdbc.sql("""
                        UPDATE mobile_device_profile SET name = :name, version = version + 1, scope = :scope,
                            manufacturer = :man, models = CAST(:models AS jsonb), installation_id = :inst,
                            min_sdk = :min, max_sdk = :max, recommended_adapter = :adapter, prerequisites = :pre,
                            confidence = :conf, status = :status, test_procedure = :proc, updated_at = :now,
                            updated_by = :actor
                        WHERE company_id = :c AND id = :id
                        """)
                .param("id", id).param("c", companyId).params(fields(p)).param("now", OffsetDateTime.now(clock))
                .param("actor", actor).update();
        audit.record(companyId, "PROFILE_UPDATED", "DEVICE_PROFILE", id, actor, null,
                "v" + before.version() + " -> v" + (before.version() + 1) + " " + summary(p));
        return get(companyId, id);
    }

    @Transactional
    public Profile retire(UUID companyId, UUID id, String actor) {
        Profile before = get(companyId, id);
        if (!"RETIRED".equals(before.status())) {
            jdbc.sql("""
                            UPDATE mobile_device_profile SET status = 'RETIRED', version = version + 1,
                                updated_at = :now, updated_by = :actor
                            WHERE company_id = :c AND id = :id
                            """)
                    .param("now", OffsetDateTime.now(clock)).param("actor", actor).param("c", companyId)
                    .param("id", id).update();
            audit.record(companyId, "PROFILE_RETIRED", "DEVICE_PROFILE", id, actor, null, before.name());
        }
        return get(companyId, id);
    }

    private ProfileInput validate(ProfileInput p) {
        String name = MobileDevices.cut(p.name(), 80, null);
        String scope = p.scope() == null ? "MODEL" : p.scope().strip().toUpperCase();
        String manufacturer = MobileDevices.cut(p.manufacturer(), 80, null);
        String confidence = p.confidence() == null ? "MEDIUM" : p.confidence().strip().toUpperCase();
        String status = p.status() == null ? "EXPERIMENTAL" : p.status().strip().toUpperCase();
        List<String> models = p.models() == null ? List.of()
                : p.models().stream().map(String::strip).filter(m -> !m.isEmpty()).distinct().toList();
        if (name == null || manufacturer == null || !SCOPES.contains(scope) || !CONFIDENCES.contains(confidence)
                || !EDITABLE_STATUSES.contains(status)) {
            throw new MystixException(ErrorCode.VALIDATION_FAILED, "name, manufacturer, scope, confidence, status");
        }
        if ("MODEL".equals(scope) && models.isEmpty()) {
            throw new MystixException(ErrorCode.VALIDATION_FAILED, "A model profile needs at least one model");
        }
        String installation = "INSTALLATION".equals(scope) ? MobileDevices.cut(p.installationId(), 64, null) : null;
        if ("INSTALLATION".equals(scope) && installation == null) {
            throw new MystixException(ErrorCode.VALIDATION_FAILED, "An installation profile needs an installation id");
        }
        if (p.minSdk() != null && p.maxSdk() != null && p.minSdk() > p.maxSdk()) {
            throw new MystixException(ErrorCode.VALIDATION_FAILED, "minSdk is above maxSdk");
        }
        MobileDevices.Adapter adapter = MobileDevices.adapter(p.recommendedAdapter());
        if (adapter.honeywellOnly() && !"honeywell".equalsIgnoreCase(manufacturer)) {
            throw new MystixException(ErrorCode.DEVICE_ADAPTER_UNKNOWN, adapter.id() + " only runs on Honeywell terminals");
        }
        return new ProfileInput(name, scope, manufacturer, models, installation, p.minSdk(), p.maxSdk(), adapter.id(),
                MobileDevices.cut(p.prerequisites(), 2000, null), confidence, status,
                MobileDevices.cut(p.testProcedure(), 4000, null));
    }

    private java.util.Map<String, Object> fields(ProfileInput p) {
        var m = new java.util.HashMap<String, Object>();
        m.put("name", p.name());
        m.put("scope", p.scope());
        m.put("man", p.manufacturer());
        m.put("models", json.writeValueAsString(p.models()));
        m.put("inst", p.installationId());
        m.put("min", p.minSdk());
        m.put("max", p.maxSdk());
        m.put("adapter", p.recommendedAdapter());
        m.put("pre", p.prerequisites());
        m.put("conf", p.confidence());
        m.put("status", p.status());
        m.put("proc", p.testProcedure());
        return m;
    }

    private static String summary(ProfileInput p) {
        return p.name() + " " + p.scope() + " " + p.manufacturer() + " " + p.models() + " -> " + p.recommendedAdapter()
                + " (" + p.status() + ")";
    }

    private Profile profile(ResultSet rs, int n) throws SQLException {
        return new Profile(rs.getObject("id", UUID.class), rs.getString("name"), rs.getInt("version"),
                rs.getString("scope"), rs.getString("manufacturer"),
                json.readValue(rs.getString("models"), new TypeReference<List<String>>() { }),
                rs.getString("installation_id"), (Integer) rs.getObject("min_sdk"), (Integer) rs.getObject("max_sdk"),
                rs.getString("recommended_adapter"), rs.getString("prerequisites"), rs.getString("confidence"),
                rs.getString("status"), rs.getString("test_procedure"),
                rs.getObject("updated_at", OffsetDateTime.class), rs.getString("updated_by"));
    }
}
