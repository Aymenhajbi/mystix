package ma.mystix.device;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import ma.mystix.shared.error.ErrorCode;
import ma.mystix.shared.error.MystixException;
import ma.mystix.wms.AtelysAudit;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * Terminals of Atelys Mobile (ADR-0019, lot D). A terminal checks in with what it detected and what it activated;
 * administrators label it, suggest an adapter, ask for a reset or a scan test. The terminal stays the authority on
 * what runs: a suggestion is never activated without a scan test on the terminal.
 */
@Service
public class MobileDevices {

    /** Adapters compiled in the current Atelys Mobile release (ADR-0019 §2); keep in sync with the app registry. */
    public record Adapter(String id, String name, boolean honeywellOnly, boolean experimental) {
    }

    public static final List<Adapter> ADAPTERS = List.of(
            new Adapter("honeywell-data-intent", "Honeywell Data Intent", true, true),
            new Adapter("keyboard-wedge", "Keyboard wedge", false, false));

    public enum Health { OK, ACTION_REQUIRED, ERROR, OFFLINE }

    public record Device(UUID id, String installationId, String label, String manufacturer, String brand, String model,
                         String product, String androidRelease, int sdkInt, String buildId, String appVersion,
                         List<String> capabilities, String resolutionState, String recommendedAdapter,
                         List<String> resolutionReasons, String activeAdapter, OffsetDateTime validatedAt,
                         String preferredAdapter, String preferredBy, OffsetDateTime preferredAt,
                         OffsetDateTime resetRequestedAt, OffsetDateTime testRequestedAt, String lastErrorCode,
                         OffsetDateTime lastErrorAt, OffsetDateTime lastSeenAt, String lastSeenBy,
                         OffsetDateTime createdAt, Health health) {
    }

    public record Diagnostic(UUID id, String adapterId, String outcome, boolean simulated, String symbology,
                             Integer dataLength, String errorCode, OffsetDateTime startedAt,
                             OffsetDateTime finishedAt, OffsetDateTime receivedAt) {
    }

    public record CheckIn(String installationId, String manufacturer, String brand, String model, String product,
                          String androidRelease, int sdkInt, String buildId, String appVersion,
                          List<String> capabilities, String resolutionState, String recommendedAdapter,
                          List<String> resolutionReasons, String activeAdapter, OffsetDateTime validatedAt,
                          String lastErrorCode, OffsetDateTime lastErrorAt) {
    }

    /** What the terminal applies after a check-in. */
    public record CheckInResult(UUID deviceId, String label, String preferredAdapter, boolean resetRequested,
                                boolean testRequested, List<DeviceProfiles.Profile> profiles) {
    }

    public record NewDiagnostic(UUID id, String adapterId, String outcome, boolean simulated, String symbology,
                                Integer dataLength, String errorCode, OffsetDateTime startedAt,
                                OffsetDateTime finishedAt) {
    }

    private final JdbcClient jdbc;
    private final DeviceProfiles profiles;
    private final AtelysAudit audit;
    private final JsonMapper json;
    private final Clock clock;
    private final Duration offlineAfter;

    MobileDevices(JdbcClient jdbc, DeviceProfiles profiles, AtelysAudit audit, JsonMapper json, Clock clock,
                  @Value("${mystix.devices.offline-after:PT24H}") Duration offlineAfter) {
        this.jdbc = jdbc;
        this.profiles = profiles;
        this.audit = audit;
        this.json = json;
        this.clock = clock;
        this.offlineAfter = offlineAfter;
    }

    public static Adapter adapter(String id) {
        return ADAPTERS.stream().filter(a -> a.id().equals(id)).findFirst()
                .orElseThrow(() -> new MystixException(ErrorCode.DEVICE_ADAPTER_UNKNOWN, "Unknown adapter " + id));
    }

    /** Registers the terminal on its first call, refreshes it afterwards. Same installation id = same row. */
    @Transactional
    public CheckInResult checkIn(UUID companyId, CheckIn c, String actor) {
        if (c.installationId() == null || !c.installationId().matches("[A-Za-z0-9-]{8,64}")) {
            throw new MystixException(ErrorCode.VALIDATION_FAILED, "installationId must be 8 to 64 letters, digits or -");
        }
        OffsetDateTime now = OffsetDateTime.now(clock);
        UUID id = UUID.randomUUID();
        UUID deviceId = jdbc.sql("""
                        INSERT INTO mobile_device (id, company_id, installation_id, manufacturer, brand, model, product,
                                                   android_release, sdk_int, build_id, app_version, capabilities,
                                                   resolution_state, recommended_adapter, resolution_reasons,
                                                   active_adapter, validated_at, last_error_code, last_error_at,
                                                   last_seen_at, last_seen_by, created_at, updated_at)
                        VALUES (:id, :c, :inst, :man, :brand, :model, :product, :rel, :sdk, :build, :app,
                                CAST(:caps AS jsonb), :state, :rec, CAST(:reasons AS jsonb), :active, :validated,
                                :err, :errAt, :now, :actor, :now, :now)
                        ON CONFLICT (company_id, installation_id) DO UPDATE SET
                            manufacturer = EXCLUDED.manufacturer, brand = EXCLUDED.brand, model = EXCLUDED.model,
                            product = EXCLUDED.product, android_release = EXCLUDED.android_release,
                            sdk_int = EXCLUDED.sdk_int, build_id = EXCLUDED.build_id,
                            app_version = EXCLUDED.app_version, capabilities = EXCLUDED.capabilities,
                            resolution_state = EXCLUDED.resolution_state,
                            recommended_adapter = EXCLUDED.recommended_adapter,
                            resolution_reasons = EXCLUDED.resolution_reasons,
                            active_adapter = EXCLUDED.active_adapter, validated_at = EXCLUDED.validated_at,
                            last_error_code = COALESCE(EXCLUDED.last_error_code, mobile_device.last_error_code),
                            last_error_at = COALESCE(EXCLUDED.last_error_at, mobile_device.last_error_at),
                            last_seen_at = EXCLUDED.last_seen_at, last_seen_by = EXCLUDED.last_seen_by,
                            updated_at = EXCLUDED.updated_at,
                            -- A reset is done once the terminal reports no configuration, or a newer validation.
                            reset_requested_at = CASE
                                WHEN EXCLUDED.active_adapter IS NULL
                                     OR EXCLUDED.validated_at > mobile_device.reset_requested_at THEN NULL
                                ELSE mobile_device.reset_requested_at END
                        RETURNING id
                        """)
                .param("id", id).param("c", companyId).param("inst", c.installationId())
                .param("man", cut(c.manufacturer(), 80, "unknown")).param("brand", cut(c.brand(), 80, null))
                .param("model", cut(c.model(), 80, "unknown")).param("product", cut(c.product(), 80, null))
                .param("rel", cut(c.androidRelease(), 20, null)).param("sdk", c.sdkInt())
                .param("build", cut(c.buildId(), 100, null)).param("app", cut(c.appVersion(), 40, null))
                .param("caps", json.writeValueAsString(c.capabilities() == null ? List.of() : c.capabilities()))
                .param("state", c.resolutionState()).param("rec", cut(c.recommendedAdapter(), 60, null))
                .param("reasons", json.writeValueAsString(c.resolutionReasons() == null ? List.of() : c.resolutionReasons()))
                .param("active", c.validatedAt() == null ? null : cut(c.activeAdapter(), 60, null))
                .param("validated", c.activeAdapter() == null ? null : c.validatedAt())
                .param("err", cut(c.lastErrorCode(), 40, null)).param("errAt", c.lastErrorAt())
                .param("now", now).param("actor", actor)
                .query(UUID.class).single();
        if (deviceId.equals(id)) {
            audit.record(companyId, "DEVICE_REGISTERED", "DEVICE", deviceId, actor, null,
                    c.manufacturer() + " " + c.model());
        }
        Device d = get(companyId, deviceId);
        return new CheckInResult(deviceId, d.label(), d.preferredAdapter(), d.resetRequestedAt() != null,
                d.testRequestedAt() != null, profiles.active(companyId));
    }

    /** Stores a scan test sent by the terminal. The id comes from the terminal: a resend is ignored. */
    @Transactional
    public Diagnostic recordDiagnostic(UUID companyId, UUID deviceId, NewDiagnostic d, String actor) {
        get(companyId, deviceId);
        if (d.id() == null || d.adapterId() == null || d.outcome() == null || d.startedAt() == null
                || d.finishedAt() == null) {
            throw new MystixException(ErrorCode.VALIDATION_FAILED, "id, adapterId, outcome, startedAt, finishedAt required");
        }
        int inserted = jdbc.sql("""
                        INSERT INTO mobile_device_diagnostic (id, company_id, device_id, adapter_id, outcome, simulated,
                                                              symbology, data_length, error_code, started_at,
                                                              finished_at, received_at)
                        VALUES (:id, :c, :d, :a, :o, :sim, :sym, :len, :err, :start, :end, :now)
                        ON CONFLICT (id) DO NOTHING
                        """)
                .param("id", d.id()).param("c", companyId).param("d", deviceId).param("a", cut(d.adapterId(), 60, null))
                .param("o", d.outcome()).param("sim", d.simulated()).param("sym", cut(d.symbology(), 40, null))
                .param("len", d.dataLength()).param("err", cut(d.errorCode(), 40, null))
                .param("start", d.startedAt()).param("end", d.finishedAt()).param("now", OffsetDateTime.now(clock))
                .update();
        if (inserted == 1) {
            boolean failed = !"PASSED".equals(d.outcome()) && !"CANCELLED".equals(d.outcome());
            jdbc.sql("""
                            UPDATE mobile_device SET
                                last_error_code = CASE WHEN :failed THEN :code ELSE last_error_code END,
                                last_error_at = CASE WHEN :failed THEN :at ELSE last_error_at END,
                                test_requested_at = CASE WHEN test_requested_at <= :at THEN NULL ELSE test_requested_at END,
                                updated_at = :now
                            WHERE company_id = :c AND id = :d
                            """)
                    .param("failed", failed).param("code", d.errorCode() != null ? d.errorCode() : d.outcome())
                    .param("at", d.finishedAt()).param("now", OffsetDateTime.now(clock))
                    .param("c", companyId).param("d", deviceId).update();
            audit.record(companyId, "DEVICE_DIAGNOSTIC", "DEVICE", deviceId, actor, null,
                    d.adapterId() + " " + d.outcome() + (d.simulated() ? " (simulated)" : ""));
        }
        return jdbc.sql("SELECT * FROM mobile_device_diagnostic WHERE company_id = :c AND id = :id")
                .param("c", companyId).param("id", d.id()).query(this::diagnostic).single();
    }

    public List<Device> list(UUID companyId) {
        return jdbc.sql("SELECT * FROM mobile_device WHERE company_id = :c ORDER BY last_seen_at DESC")
                .param("c", companyId).query(this::device).list();
    }

    public Device get(UUID companyId, UUID deviceId) {
        return jdbc.sql("SELECT * FROM mobile_device WHERE company_id = :c AND id = :id")
                .param("c", companyId).param("id", deviceId).query(this::device).optional()
                .orElseThrow(() -> new MystixException(ErrorCode.ATELYS_NOT_FOUND, "Not found: device " + deviceId));
    }

    public List<Diagnostic> diagnostics(UUID companyId, UUID deviceId, int limit) {
        return jdbc.sql("""
                        SELECT * FROM mobile_device_diagnostic WHERE company_id = :c AND device_id = :d
                        ORDER BY finished_at DESC LIMIT :limit
                        """)
                .param("c", companyId).param("d", deviceId).param("limit", limit).query(this::diagnostic).list();
    }

    @Transactional
    public Device rename(UUID companyId, UUID deviceId, String label, String actor) {
        Device before = get(companyId, deviceId);
        String clean = cut(label, 80, null);
        update(companyId, deviceId, "label = :v", clean);
        audit.record(companyId, "DEVICE_RENAMED", "DEVICE", deviceId, actor, null, show(before.label()) + " -> " + show(clean));
        return get(companyId, deviceId);
    }

    /** A suggestion shown on the terminal; "none" removes it. It is activated only after a scan test there. */
    @Transactional
    public Device prefer(UUID companyId, UUID deviceId, String adapterId, String actor) {
        Device d = get(companyId, deviceId);
        String value = adapterId == null || adapterId.isBlank() || "none".equals(adapterId) ? null : adapter(adapterId).id();
        if (value != null && adapter(value).honeywellOnly() && !"honeywell".equalsIgnoreCase(d.manufacturer().strip())) {
            throw new MystixException(ErrorCode.DEVICE_ADAPTER_UNKNOWN, value + " only runs on Honeywell terminals");
        }
        jdbc.sql("""
                        UPDATE mobile_device SET preferred_adapter = :v, preferred_by = :actor,
                                                 preferred_at = :now, updated_at = :now
                        WHERE company_id = :c AND id = :id
                        """)
                .param("v", value).param("actor", actor).param("now", OffsetDateTime.now(clock))
                .param("c", companyId).param("id", deviceId).update();
        audit.record(companyId, "DEVICE_PREFERENCE", "DEVICE", deviceId, actor, null,
                show(d.preferredAdapter()) + " -> " + show(value));
        return get(companyId, deviceId);
    }

    @Transactional
    public Device requestReset(UUID companyId, UUID deviceId, String actor) {
        get(companyId, deviceId);
        update(companyId, deviceId, "reset_requested_at = :v", OffsetDateTime.now(clock));
        audit.record(companyId, "DEVICE_RESET_REQUESTED", "DEVICE", deviceId, actor, null, null);
        return get(companyId, deviceId);
    }

    @Transactional
    public Device requestTest(UUID companyId, UUID deviceId, String actor) {
        get(companyId, deviceId);
        update(companyId, deviceId, "test_requested_at = :v", OffsetDateTime.now(clock));
        audit.record(companyId, "DEVICE_TEST_REQUESTED", "DEVICE", deviceId, actor, null, null);
        return get(companyId, deviceId);
    }

    private void update(UUID companyId, UUID deviceId, String assignment, Object value) {
        jdbc.sql("UPDATE mobile_device SET " + assignment + ", updated_at = :now WHERE company_id = :c AND id = :id")
                .param("v", value).param("now", OffsetDateTime.now(clock)).param("c", companyId)
                .param("id", deviceId).update();
    }

    Health health(OffsetDateTime lastSeen, String active, OffsetDateTime validatedAt, OffsetDateTime errorAt,
                  OffsetDateTime resetAt, String state) {
        OffsetDateTime now = OffsetDateTime.now(clock);
        if (lastSeen.isBefore(now.minus(offlineAfter))) {
            return Health.OFFLINE;
        }
        if (errorAt != null && errorAt.isAfter(now.minus(offlineAfter)) && (validatedAt == null || errorAt.isAfter(validatedAt))) {
            return Health.ERROR;
        }
        if (active == null || resetAt != null || !"AUTO_SELECTED".equals(state)) {
            return Health.ACTION_REQUIRED;
        }
        return Health.OK;
    }

    private Device device(ResultSet rs, int n) throws SQLException {
        OffsetDateTime lastSeen = rs.getObject("last_seen_at", OffsetDateTime.class);
        OffsetDateTime validatedAt = rs.getObject("validated_at", OffsetDateTime.class);
        OffsetDateTime errorAt = rs.getObject("last_error_at", OffsetDateTime.class);
        OffsetDateTime resetAt = rs.getObject("reset_requested_at", OffsetDateTime.class);
        return new Device(rs.getObject("id", UUID.class), rs.getString("installation_id"), rs.getString("label"),
                rs.getString("manufacturer"), rs.getString("brand"), rs.getString("model"), rs.getString("product"),
                rs.getString("android_release"), rs.getInt("sdk_int"), rs.getString("build_id"),
                rs.getString("app_version"), strings(rs.getString("capabilities")), rs.getString("resolution_state"),
                rs.getString("recommended_adapter"), strings(rs.getString("resolution_reasons")),
                rs.getString("active_adapter"), validatedAt, rs.getString("preferred_adapter"),
                rs.getString("preferred_by"), rs.getObject("preferred_at", OffsetDateTime.class), resetAt,
                rs.getObject("test_requested_at", OffsetDateTime.class), rs.getString("last_error_code"), errorAt,
                lastSeen, rs.getString("last_seen_by"), rs.getObject("created_at", OffsetDateTime.class),
                health(lastSeen, rs.getString("active_adapter"), validatedAt, errorAt, resetAt,
                        rs.getString("resolution_state")));
    }

    private Diagnostic diagnostic(ResultSet rs, int n) throws SQLException {
        return new Diagnostic(rs.getObject("id", UUID.class), rs.getString("adapter_id"), rs.getString("outcome"),
                rs.getBoolean("simulated"), rs.getString("symbology"), (Integer) rs.getObject("data_length"),
                rs.getString("error_code"), rs.getObject("started_at", OffsetDateTime.class),
                rs.getObject("finished_at", OffsetDateTime.class), rs.getObject("received_at", OffsetDateTime.class));
    }

    private List<String> strings(String jsonArray) {
        return json.readValue(jsonArray, new TypeReference<List<String>>() { });
    }

    private static String show(String value) {
        return value == null ? "—" : value;
    }

    static String cut(String s, int max, String fallback) {
        if (s == null || s.isBlank()) {
            return fallback;
        }
        String t = s.strip();
        return t.length() > max ? t.substring(0, max) : t;
    }
}
