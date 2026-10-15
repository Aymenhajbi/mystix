package ma.mystix.connector;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import ma.mystix.shared.error.ErrorCode;
import ma.mystix.shared.error.MystixException;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * Rows of {@code listener} and {@code listener_event} (ADR-0016). Every company-facing read is scoped by
 * {@code company_id}; the supervisor alone reads across companies, to reconcile what should run.
 */
@Component
class ListenerStore {

    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() { };

    /** A row of the journal, for the API. */
    record Event(UUID id, String eventType, String severity, UUID correlationId, Map<String, Object> details,
                 String actor, OffsetDateTime createdAt) {
    }

    private final JdbcClient jdbc;
    private final JsonMapper json;
    private final Clock clock;
    private final RowMapper<Listener> rows;

    ListenerStore(JdbcClient jdbc, JsonMapper json, Clock clock) {
        this.jdbc = jdbc;
        this.json = json;
        this.clock = clock;
        this.rows = (rs, n) -> new Listener(
                rs.getObject("id", UUID.class),
                rs.getObject("company_id", UUID.class),
                rs.getString("name"),
                Listener.Kind.valueOf(rs.getString("kind")),
                rs.getObject("channel_id", UUID.class),
                rs.getObject("entry_point_id", UUID.class),
                map(rs.getString("settings")),
                rs.getString("api_key_hash") != null,
                rs.getString("api_key_hint"),
                rs.getBoolean("enabled"),
                Listener.DesiredState.valueOf(rs.getString("desired_state")),
                Listener.RuntimeState.valueOf(rs.getString("runtime_state")),
                rs.getString("runtime_instance"),
                rs.getObject("last_heartbeat_at", OffsetDateTime.class),
                rs.getString("last_error") == null ? null : map(rs.getString("last_error")),
                rs.getObject("started_at", OffsetDateTime.class),
                rs.getObject("stopped_at", OffsetDateTime.class),
                rs.getObject("last_activity_at", OffsetDateTime.class),
                rs.getLong("received_count"),
                rs.getLong("rejected_count"),
                rs.getString("created_by"),
                rs.getObject("created_at", OffsetDateTime.class),
                rs.getObject("updated_at", OffsetDateTime.class));
    }

    OffsetDateTime now() {
        return OffsetDateTime.now(clock);
    }

    List<Listener> list(UUID companyId) {
        return jdbc.sql("SELECT * FROM listener WHERE company_id = :c ORDER BY name")
                .param("c", companyId).query(rows).list();
    }

    Listener get(UUID companyId, UUID id) {
        return jdbc.sql("SELECT * FROM listener WHERE company_id = :c AND id = :id")
                .param("c", companyId).param("id", id).query(rows).optional()
                .orElseThrow(() -> notFound(companyId, id));
    }

    /** Locks the row until the end of the caller's transaction: lifecycle transitions are serialised per listener. */
    Listener lock(UUID companyId, UUID id) {
        return jdbc.sql("SELECT * FROM listener WHERE company_id = :c AND id = :id FOR UPDATE")
                .param("c", companyId).param("id", id).query(rows).optional()
                .orElseThrow(() -> notFound(companyId, id));
    }

    /** Listeners an administrator wants running, all companies: what the supervisor reconciles against. */
    List<Listener> desiredRunning() {
        return jdbc.sql("SELECT * FROM listener WHERE desired_state = 'RUNNING' AND enabled")
                .query(rows).list();
    }

    String apiKeyHash(UUID id) {
        return jdbc.sql("SELECT api_key_hash FROM listener WHERE id = :id").param("id", id)
                .query(String.class).optional().orElse(null);
    }

    void insert(Listener l, String apiKeyHash) {
        jdbc.sql("""
                        INSERT INTO listener (id, company_id, name, kind, channel_id, entry_point_id, settings,
                                              api_key_hash, api_key_hint, enabled, desired_state, runtime_state,
                                              created_by, created_at, updated_at)
                        VALUES (:id, :c, :name, :kind, :channel, :entry, CAST(:settings AS jsonb), :hash, :hint,
                                :enabled, 'STOPPED', 'CONFIGURED', :actor, :now, :now)
                        """)
                .param("id", l.id()).param("c", l.companyId()).param("name", l.name()).param("kind", l.kind().name())
                .param("channel", l.channelId()).param("entry", l.entryPointId())
                .param("settings", json.writeValueAsString(l.settings())).param("hash", apiKeyHash)
                .param("hint", l.apiKeyHint()).param("enabled", l.enabled()).param("actor", l.createdBy())
                .param("now", l.createdAt()).update();
    }

    void update(UUID companyId, UUID id, String name, Map<String, Object> settings, boolean enabled) {
        jdbc.sql("""
                        UPDATE listener SET name = :name, settings = CAST(:settings AS jsonb), enabled = :enabled,
                               runtime_state = CASE WHEN NOT :enabled AND runtime_state <> 'RUNNING'
                                                    THEN 'DISABLED'
                                                    WHEN :enabled AND runtime_state = 'DISABLED' THEN 'CONFIGURED'
                                                    ELSE runtime_state END,
                               updated_at = :now
                        WHERE company_id = :c AND id = :id
                        """)
                .param("name", name).param("settings", json.writeValueAsString(settings)).param("enabled", enabled)
                .param("now", now()).param("c", companyId).param("id", id).update();
    }

    void setApiKey(UUID companyId, UUID id, String hash, String hint) {
        jdbc.sql("UPDATE listener SET api_key_hash = :h, api_key_hint = :hint, updated_at = :now "
                        + "WHERE company_id = :c AND id = :id")
                .param("h", hash).param("hint", hint).param("now", now()).param("c", companyId).param("id", id)
                .update();
    }

    void desire(UUID id, Listener.DesiredState desired, Listener.RuntimeState runtime) {
        jdbc.sql("UPDATE listener SET desired_state = :d, runtime_state = :r, updated_at = :now WHERE id = :id")
                .param("d", desired.name()).param("r", runtime.name()).param("now", now()).param("id", id).update();
    }

    void running(UUID id, String instance) {
        OffsetDateTime now = now();
        jdbc.sql("""
                        UPDATE listener SET runtime_state = 'RUNNING', runtime_instance = :i, last_heartbeat_at = :now,
                               started_at = :now, last_error = NULL, updated_at = :now
                        WHERE id = :id
                        """)
                .param("i", instance).param("now", now).param("id", id).update();
    }

    void failed(UUID id, String instance, Map<String, Object> error) {
        jdbc.sql("""
                        UPDATE listener SET runtime_state = 'ERROR', runtime_instance = :i,
                               last_error = CAST(:e AS jsonb), updated_at = :now
                        WHERE id = :id
                        """)
                .param("i", instance).param("e", json.writeValueAsString(error)).param("now", now()).param("id", id)
                .update();
    }

    void stopped(UUID id) {
        OffsetDateTime now = now();
        jdbc.sql("UPDATE listener SET runtime_state = 'STOPPED', stopped_at = :now, updated_at = :now WHERE id = :id")
                .param("now", now).param("id", id).update();
    }

    /** Heartbeat of a started listener: RUNNING when healthy, DEGRADED with the diagnostic otherwise. */
    void heartbeat(UUID id, String instance, ListenerAdapter.Health health) {
        jdbc.sql("""
                        UPDATE listener SET runtime_state = CASE WHEN :ok THEN 'RUNNING' ELSE 'DEGRADED' END,
                               runtime_instance = :i, last_heartbeat_at = :now,
                               last_error = CASE WHEN :ok THEN NULL ELSE CAST(:e AS jsonb) END
                        WHERE id = :id AND desired_state = 'RUNNING' AND runtime_state IN ('RUNNING', 'DEGRADED')
                        """)
                .param("ok", health.healthy()).param("i", instance).param("now", now())
                .param("e", json.writeValueAsString(errorOf(health.code(), health.detail())))
                .param("id", id).update();
    }

    void received(UUID id, boolean accepted) {
        jdbc.sql(accepted
                        ? "UPDATE listener SET received_count = received_count + 1, last_activity_at = :now WHERE id = :id"
                        : "UPDATE listener SET rejected_count = rejected_count + 1, last_activity_at = :now WHERE id = :id")
                .param("now", now()).param("id", id).update();
    }

    void event(UUID companyId, UUID listenerId, String type, String severity, UUID correlationId,
               Map<String, Object> details, String actor) {
        jdbc.sql("""
                        INSERT INTO listener_event (id, company_id, listener_id, event_type, severity, correlation_id,
                                                    details, actor, created_at)
                        VALUES (:id, :c, :l, :type, :sev, :corr, CAST(:details AS jsonb), :actor, :now)
                        """)
                .param("id", UUID.randomUUID()).param("c", companyId).param("l", listenerId).param("type", type)
                .param("sev", severity).param("corr", correlationId)
                .param("details", json.writeValueAsString(details == null ? Map.of() : details))
                .param("actor", actor).param("now", now()).update();
    }

    List<Event> events(UUID companyId, UUID listenerId, int limit) {
        return jdbc.sql("""
                        SELECT * FROM listener_event WHERE company_id = :c AND listener_id = :l
                        ORDER BY created_at DESC, id LIMIT :limit
                        """)
                .param("c", companyId).param("l", listenerId).param("limit", Math.clamp(limit, 1, 500))
                .query((rs, n) -> new Event(rs.getObject("id", UUID.class), rs.getString("event_type"),
                        rs.getString("severity"), rs.getObject("correlation_id", UUID.class),
                        map(rs.getString("details")), rs.getString("actor"),
                        rs.getObject("created_at", OffsetDateTime.class)))
                .list();
    }

    static Map<String, Object> errorOf(String code, String detail) {
        Map<String, Object> error = new LinkedHashMap<>();
        error.put("code", code);
        if (detail != null) {
            error.put("detail", detail);
        }
        return error;
    }

    private Map<String, Object> map(String value) {
        return json.readValue(value, MAP);
    }

    private static MystixException notFound(UUID companyId, UUID id) {
        return new MystixException(ErrorCode.LISTENER_NOT_FOUND, "No listener " + id + " for company " + companyId);
    }
}
