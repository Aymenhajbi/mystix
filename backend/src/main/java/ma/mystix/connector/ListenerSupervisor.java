package ma.mystix.connector;

import java.lang.management.ManagementFactory;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.stream.Collectors;

import ma.mystix.flow.ExchangeFlow;
import ma.mystix.flow.FlowService;
import ma.mystix.shared.error.ApiError;
import ma.mystix.shared.error.ErrorCode;
import ma.mystix.shared.error.MystixException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.annotation.Configuration;
import org.springframework.stereotype.Component;

/**
 * Runs the listeners of this process (ADR-0016): starts and stops them through their adapter, records a heartbeat,
 * and reconciles what should run ({@code desired_state}) with what runs here. After a restart, a listener an
 * administrator left RUNNING comes back (event RESTORED); one stopped elsewhere is stopped here.
 */
@Component
class ListenerSupervisor implements SmartLifecycle {

    private static final Logger LOG = LoggerFactory.getLogger(ListenerSupervisor.class);
    static final String SYSTEM = "system";

    private final ListenerStore store;
    private final ChannelService channels;
    private final EntryPointService entryPoints;
    private final FlowService flows;
    private final ConnectivityProperties properties;
    private final Map<Listener.Kind, ListenerAdapter> adapters;
    private final Map<UUID, ListenerAdapter.Handle> handles = new ConcurrentHashMap<>();
    private final Map<UUID, Boolean> lastHealthy = new ConcurrentHashMap<>();
    private final Map<UUID, Object> locks = new ConcurrentHashMap<>();
    private final String instance;
    private ScheduledExecutorService scheduler;
    private volatile boolean running;

    ListenerSupervisor(ListenerStore store, ChannelService channels, EntryPointService entryPoints, FlowService flows,
                       ConnectivityProperties properties, List<ListenerAdapter> adapters) {
        this.store = store;
        this.channels = channels;
        this.entryPoints = entryPoints;
        this.flows = flows;
        this.properties = properties;
        this.adapters = adapters.stream().collect(Collectors.toMap(ListenerAdapter::kind, Function.identity()));
        this.instance = ManagementFactory.getRuntimeMXBean().getName() + ":" + UUID.randomUUID().toString().substring(0, 8);
    }

    String instance() {
        return instance;
    }

    boolean supports(Listener.Kind kind) {
        return adapters.containsKey(kind);
    }

    boolean isLocal(UUID listenerId) {
        return handles.containsKey(listenerId);
    }

    /** What must hold before a start; empty when it may start. */
    List<ApiError.FieldViolation> preconditions(Listener listener) {
        ListenerAdapter adapter = adapter(listener.kind());
        return adapter.preconditions(context(listener));
    }

    /**
     * Starts the listener here, unless it already runs here. RUNNING is written only after the adapter started and a
     * health check passed; otherwise ERROR, with the diagnostic, and {@code LISTENER_START_FAILED}.
     *
     * @param eventType STARTED for an administrator's start, RESTORED for a reconciliation
     */
    void launch(Listener listener, String eventType, String actor) {
        synchronized (locks.computeIfAbsent(listener.id(), k -> new Object())) {
            if (handles.containsKey(listener.id())) {
                return;
            }
            ListenerAdapter adapter = adapter(listener.kind());
            ListenerAdapter.Context context = context(listener);
            List<ApiError.FieldViolation> problems = adapter.preconditions(context);
            if (!problems.isEmpty()) {
                Map<String, Object> error = ListenerStore.errorOf("NOT_READY", describe(problems));
                store.failed(listener.id(), instance, error);
                store.event(listener.companyId(), listener.id(), "START_REFUSED", "ERROR", null, error, actor);
                throw new MystixException(ErrorCode.LISTENER_NOT_READY, "Listener " + listener.id() + " not ready",
                        problems, List.of(), null);
            }
            ListenerAdapter.Handle handle;
            try {
                handle = adapter.start(context);
            } catch (RuntimeException e) {
                fail(listener, "START_ERROR", e.getClass().getSimpleName() + ": " + e.getMessage(), actor);
                throw new MystixException(ErrorCode.LISTENER_START_FAILED, "Listener " + listener.id() + " did not start", e);
            }
            ListenerAdapter.Health health = adapter.health(handle);
            if (!health.healthy()) {
                adapter.stop(handle);
                fail(listener, health.code(), health.detail(), actor);
                throw new MystixException(ErrorCode.LISTENER_START_FAILED,
                        "Listener " + listener.id() + " unhealthy after start: " + health.code());
            }
            handles.put(listener.id(), handle);
            lastHealthy.put(listener.id(), true);
            store.running(listener.id(), instance);
            store.event(listener.companyId(), listener.id(), eventType, "INFO", null,
                    Map.of("instance", instance, "kind", listener.kind().name()), actor);
            LOG.info("Listener {} ({}) {} on {}", listener.id(), listener.kind(), eventType, instance);
        }
    }

    /** Stops the listener here, if it runs here, and records STOPPED. */
    void halt(Listener listener, String eventType, String actor) {
        synchronized (locks.computeIfAbsent(listener.id(), k -> new Object())) {
            ListenerAdapter.Handle handle = handles.remove(listener.id());
            lastHealthy.remove(listener.id());
            if (handle != null) {
                adapter(handle.kind()).stop(handle);
            }
            store.stopped(listener.id());
            store.event(listener.companyId(), listener.id(), eventType, "INFO", null,
                    Map.of("instance", instance, "wasRunningHere", handle != null), actor);
            LOG.info("Listener {} {} on {}", listener.id(), eventType, instance);
        }
    }

    /** Health of each listener started here, written as a heartbeat. */
    void heartbeat() {
        for (ListenerAdapter.Handle handle : List.copyOf(handles.values())) {
            try {
                ListenerAdapter.Health health = adapter(handle.kind()).health(handle);
                store.heartbeat(handle.listenerId(), instance, health);
                Boolean before = lastHealthy.put(handle.listenerId(), health.healthy());
                if (before != null && before != health.healthy()) {
                    store.event(handle.companyId(), handle.listenerId(),
                            health.healthy() ? "HEALTH_RECOVERED" : "HEALTH_DEGRADED",
                            health.healthy() ? "INFO" : "WARN", null,
                            ListenerStore.errorOf(health.code(), health.detail()), SYSTEM);
                }
            } catch (RuntimeException e) {
                LOG.warn("Heartbeat of listener {} failed", handle.listenerId(), e);
            }
        }
    }

    /** Brings this process in line with the desired states: restores what should run, stops what should not. */
    void reconcile() {
        List<Listener> wanted = store.desiredRunning();
        Set<UUID> wantedIds = new HashSet<>();
        for (Listener listener : wanted) {
            wantedIds.add(listener.id());
            if (handles.containsKey(listener.id()) || !supports(listener.kind())) {
                continue;
            }
            try {
                launch(listener, "RESTORED", SYSTEM);
            } catch (RuntimeException e) {
                LOG.warn("Listener {} could not be restored: {}", listener.id(), e.getMessage());
            }
        }
        for (ListenerAdapter.Handle handle : List.copyOf(handles.values())) {
            if (!wantedIds.contains(handle.listenerId())) {
                try {
                    halt(store.get(handle.companyId(), handle.listenerId()), "STOPPED", SYSTEM);
                } catch (RuntimeException e) {
                    LOG.warn("Listener {} could not be stopped: {}", handle.listenerId(), e.getMessage());
                }
            }
        }
    }

    @Override
    public void start() {
        if (!properties.supervisorEnabled()) {
            LOG.info("Listener supervisor disabled (mystix.connectivity.supervisor-enabled=false)");
            return;
        }
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> Thread.ofPlatform().name("listener-supervisor")
                .daemon().unstarted(r));
        running = true;
        scheduler.scheduleWithFixedDelay(this::safeReconcile, 0, properties.reconcile().toMillis(),
                TimeUnit.MILLISECONDS);
        scheduler.scheduleWithFixedDelay(this::heartbeat, properties.heartbeat().toMillis(),
                properties.heartbeat().toMillis(), TimeUnit.MILLISECONDS);
        LOG.info("Listener supervisor {} started, adapters {}", instance, adapters.keySet());
    }

    @Override
    public void stop() {
        running = false;
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
        // The desired state stays RUNNING: the next start of the application restores these listeners.
        for (ListenerAdapter.Handle handle : List.copyOf(handles.values())) {
            try {
                adapter(handle.kind()).stop(handle);
                handles.remove(handle.listenerId());
                store.stopped(handle.listenerId());
                store.event(handle.companyId(), handle.listenerId(), "NODE_SHUTDOWN", "WARN", null,
                        Map.of("instance", instance), SYSTEM);
            } catch (RuntimeException e) {
                LOG.warn("Listener {} not recorded as stopped at shutdown: {}", handle.listenerId(), e.getMessage());
            }
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    /** Before the web server stops, so no message is accepted by a listener being closed. */
    @Override
    public int getPhase() {
        return SmartLifecycle.DEFAULT_PHASE - 2048;
    }

    private void safeReconcile() {
        try {
            reconcile();
        } catch (RuntimeException e) {
            LOG.warn("Listener reconciliation failed", e);
        }
    }

    private void fail(Listener listener, String code, String detail, String actor) {
        Map<String, Object> error = ListenerStore.errorOf(code, detail);
        store.failed(listener.id(), instance, error);
        store.event(listener.companyId(), listener.id(), "START_FAILED", "ERROR", null, error, actor);
        LOG.warn("Listener {} failed to start: {} {}", listener.id(), code, detail);
    }

    private ListenerAdapter adapter(Listener.Kind kind) {
        ListenerAdapter adapter = adapters.get(kind);
        if (adapter == null) {
            throw new MystixException(ErrorCode.LISTENER_KIND_UNAVAILABLE, "No adapter for " + kind);
        }
        return adapter;
    }

    private ListenerAdapter.Context context(Listener listener) {
        Channel channel = channels.get(listener.companyId(), listener.channelId());
        EntryPoint entry = entryPoints.get(listener.companyId(), listener.entryPointId());
        ExchangeFlow flow = flows.get(listener.companyId(), entry.flowId());
        return new ListenerAdapter.Context(listener, channel, entry, flow, store.apiKeyHash(listener.id()));
    }

    private static String describe(List<ApiError.FieldViolation> problems) {
        Map<String, String> byField = new LinkedHashMap<>();
        problems.forEach(p -> byField.put(p.field(), p.reason()));
        return byField.entrySet().stream().map(e -> e.getKey() + ": " + e.getValue()).collect(Collectors.joining("; "));
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(ConnectivityProperties.class)
    static class Config {
    }
}
