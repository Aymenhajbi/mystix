package ma.mystix.connector;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import ma.mystix.shared.error.ApiError;
import ma.mystix.shared.error.ErrorCode;
import ma.mystix.shared.error.MystixException;
import ma.mystix.shared.Sha256;
import ma.mystix.tenant.CompanyService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Listeners of each client environment (ADR-0016): configuration, API key, and the start and stop an administrator
 * asks for. A start is never implied by saving: it is an explicit action whose preconditions are checked, and RUNNING
 * is written by the supervisor only once the adapter runs and passed its health check.
 */
@Service
public class ListenerService {

    private static final Logger LOG = LoggerFactory.getLogger(ListenerService.class);
    private static final SecureRandom RANDOM = new SecureRandom();
    /** A start older than this that never reached RUNNING is considered lost: a new start may try again. */
    private static final Duration START_GRACE = Duration.ofSeconds(30);

    public record Config(String name, Listener.Kind kind, UUID channelId, UUID entryPointId,
                         Map<String, Object> settings, Boolean enabled) {
    }

    /** A new listener and its API key, shown this once. */
    public record Created(Listener listener, String apiKey) {
    }

    private final ListenerStore store;
    private final ListenerSupervisor supervisor;
    private final ChannelService channels;
    private final EntryPointService entryPoints;
    private final CompanyService companies;
    private final ConnectivityProperties properties;
    private final TransactionTemplate tx;

    ListenerService(ListenerStore store, ListenerSupervisor supervisor, ChannelService channels,
                    EntryPointService entryPoints, CompanyService companies, ConnectivityProperties properties,
                    TransactionTemplate tx) {
        this.store = store;
        this.supervisor = supervisor;
        this.channels = channels;
        this.entryPoints = entryPoints;
        this.companies = companies;
        this.properties = properties;
        this.tx = tx;
    }

    public List<Listener> list(UUID companyId) {
        companies.get(companyId);
        return store.list(companyId);
    }

    public Listener get(UUID companyId, UUID id) {
        return store.get(companyId, id);
    }

    /** The state to show, from the runtime state and the age of the last heartbeat. */
    public Listener.RuntimeState observedState(Listener listener) {
        return listener.observedState(store.now(), properties.staleAfter());
    }

    public List<ListenerStore.Event> events(UUID companyId, UUID id, int limit) {
        store.get(companyId, id);
        return store.events(companyId, id, limit);
    }

    public Created create(UUID companyId, Config config, String actor) {
        companies.get(companyId);
        Map<String, Object> settings = check(companyId, config);
        String key = newKey();
        OffsetDateTime now = store.now();
        Listener listener = new Listener(UUID.randomUUID(), companyId, config.name().strip(), config.kind(),
                config.channelId(), config.entryPointId(), settings, true, hint(key),
                config.enabled() == null || config.enabled(), Listener.DesiredState.STOPPED,
                Listener.RuntimeState.CONFIGURED, null, null, null, null, null, null, 0, 0, actor, now, now);
        try {
            tx.executeWithoutResult(s -> {
                store.insert(listener, Sha256.hex(key.getBytes(java.nio.charset.StandardCharsets.US_ASCII)));
                store.event(companyId, listener.id(), "CREATED", "INFO", null,
                        Map.of("kind", config.kind().name()), actor);
            });
        } catch (DuplicateKeyException e) {
            throw new MystixException(ErrorCode.LISTENER_NAME_TAKEN, "Listener name taken: " + config.name(), e);
        }
        LOG.info("Listener {} ({}) created for company {} by {}", listener.id(), config.kind(), companyId, actor);
        return new Created(store.get(companyId, listener.id()), key);
    }

    /** Name, settings and enabled flag; a running listener keeps running with its started settings until restarted. */
    public Listener update(UUID companyId, UUID id, String name, Map<String, Object> settings, Boolean enabled,
                           String actor) {
        Listener current = store.get(companyId, id);
        Config config = new Config(name == null ? current.name() : name, current.kind(), current.channelId(),
                current.entryPointId(), settings == null ? current.settings() : settings,
                enabled == null ? current.enabled() : enabled);
        Map<String, Object> checked = check(companyId, config);
        boolean disabling = current.enabled() && !config.enabled();
        if (disabling && supervisor.isLocal(id)) {
            stop(companyId, id, actor);
        }
        try {
            tx.executeWithoutResult(s -> {
                store.update(companyId, id, config.name().strip(), checked, config.enabled());
                store.event(companyId, id, "UPDATED", "INFO", null, Map.of("enabled", config.enabled()), actor);
            });
        } catch (DuplicateKeyException e) {
            throw new MystixException(ErrorCode.LISTENER_NAME_TAKEN, "Listener name taken: " + name, e);
        }
        return store.get(companyId, id);
    }

    /** A new API key, shown this once; the previous one stops working at the next start. */
    public Created rotateKey(UUID companyId, UUID id, String actor) {
        store.get(companyId, id);
        String key = newKey();
        tx.executeWithoutResult(s -> {
            store.setApiKey(companyId, id, Sha256.hex(key.getBytes(java.nio.charset.StandardCharsets.US_ASCII)),
                    hint(key));
            store.event(companyId, id, "API_KEY_ROTATED", "INFO", null, Map.of("hint", hint(key)), actor);
        });
        if (supervisor.isLocal(id)) {
            // The running route holds the old key: restart it so the new key applies at once.
            Listener listener = store.get(companyId, id);
            supervisor.halt(listener, "STOPPED", actor);
            supervisor.launch(listener, "STARTED", actor);
        }
        return new Created(store.get(companyId, id), key);
    }

    private enum Decision { START, ALREADY, REFUSED }

    /**
     * Starts the listener: preconditions, then the adapter, then a health check. Idempotent: a listener already
     * running, or whose start is under way, is returned as is. Concurrent starts are serialised on the row.
     */
    public Listener start(UUID companyId, UUID id, String actor) {
        List<ApiError.FieldViolation> problems = new ArrayList<>();
        Decision decision = tx.execute(s -> {
            Listener l = store.lock(companyId, id);
            if (!l.enabled()) {
                throw new MystixException(ErrorCode.LISTENER_DISABLED, "Listener " + id + " is disabled");
            }
            if (!supervisor.supports(l.kind())) {
                throw new MystixException(ErrorCode.LISTENER_KIND_UNAVAILABLE, "No adapter for " + l.kind());
            }
            boolean starting = l.runtimeState() == Listener.RuntimeState.STARTING
                    && l.updatedAt().plus(START_GRACE).isAfter(store.now());
            if (l.desiredState() == Listener.DesiredState.RUNNING && (supervisor.isLocal(id) || starting)) {
                return Decision.ALREADY;
            }
            problems.addAll(supervisor.preconditions(l));
            if (!problems.isEmpty()) {
                return Decision.REFUSED;
            }
            store.desire(id, Listener.DesiredState.RUNNING, Listener.RuntimeState.STARTING);
            store.event(companyId, id, "START_REQUESTED", "INFO", null, Map.of(), actor);
            return Decision.START;
        });
        if (decision == Decision.REFUSED) {
            Map<String, Object> details = new LinkedHashMap<>();
            problems.forEach(p -> details.put(p.field(), p.reason()));
            store.event(companyId, id, "START_REFUSED", "WARN", null, details, actor);
            throw new MystixException(ErrorCode.LISTENER_NOT_READY, "Listener " + id + " not ready", problems,
                    List.of(), null);
        }
        if (decision == Decision.START) {
            supervisor.launch(store.get(companyId, id), "STARTED", actor);
        }
        return store.get(companyId, id);
    }

    /** Stops the listener: no new message is accepted from the moment the route is closed. Idempotent. */
    public Listener stop(UUID companyId, UUID id, String actor) {
        boolean act = Boolean.TRUE.equals(tx.execute(s -> {
            Listener l = store.lock(companyId, id);
            if (l.desiredState() == Listener.DesiredState.STOPPED && !supervisor.isLocal(id)
                    && l.runtimeState() != Listener.RuntimeState.RUNNING) {
                return false;
            }
            store.desire(id, Listener.DesiredState.STOPPED, Listener.RuntimeState.STOPPING);
            store.event(companyId, id, "STOP_REQUESTED", "INFO", null, Map.of(), actor);
            return true;
        }));
        if (act) {
            supervisor.halt(store.get(companyId, id), "STOPPED", actor);
        }
        return store.get(companyId, id);
    }

    private Map<String, Object> check(UUID companyId, Config c) {
        List<ApiError.FieldViolation> violations = new ArrayList<>();
        if (c.name() == null || c.name().isBlank() || c.name().strip().length() > 120) {
            violations.add(new ApiError.FieldViolation("name", "required, at most 120 characters"));
        }
        if (c.kind() == null) {
            violations.add(new ApiError.FieldViolation("kind", "required"));
        }
        if (c.channelId() == null) {
            violations.add(new ApiError.FieldViolation("channelId", "required"));
        }
        if (c.entryPointId() == null) {
            violations.add(new ApiError.FieldViolation("entryPointId", "required"));
        }
        Map<String, Object> settings = new LinkedHashMap<>();
        Map<String, Object> raw = c.settings() == null ? Map.of() : c.settings();
        for (String key : raw.keySet()) {
            if (!key.equals("maxBytes")) {
                violations.add(new ApiError.FieldViolation("settings." + key, "unknown parameter"));
            }
        }
        if (raw.get("maxBytes") != null) {
            settings.put("maxBytes", HttpInboundAdapter.maxBytes(raw, violations));
        }
        if (!violations.isEmpty()) {
            throw new MystixException(ErrorCode.VALIDATION_FAILED, "Invalid listener", violations, List.of(), null);
        }
        if (!supervisor.supports(c.kind())) {
            throw new MystixException(ErrorCode.LISTENER_KIND_UNAVAILABLE, "No adapter for " + c.kind());
        }
        channels.get(companyId, c.channelId());
        entryPoints.get(companyId, c.entryPointId());
        return settings;
    }

    /** 32 random bytes, URL-safe Base64 with a prefix that says what it is. */
    private static String newKey() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return "mxk_" + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String hint(String key) {
        return key.substring(key.length() - 4);
    }
}
