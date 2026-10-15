package ma.mystix.connector;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import jakarta.servlet.http.HttpServletRequest;
import ma.mystix.auth.Access;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Connectivity Manager API (ADR-0016): entry points and listeners of the environment named by
 * {@code X-Mystix-Company-Id}. Writes, starts and stops need an administrator of the environment or an operator.
 */
@RestController
@RequestMapping(value = "/api/v1/connectivity", produces = MediaType.APPLICATION_JSON_VALUE)
class ConnectivityController {

    private static final String COMPANY = "X-Mystix-Company-Id";

    private final EntryPointService entryPoints;
    private final ListenerService listeners;
    private final Access access;

    ConnectivityController(EntryPointService entryPoints, ListenerService listeners, Access access) {
        this.entryPoints = entryPoints;
        this.listeners = listeners;
        this.access = access;
    }

    // ---------- entry points ----------

    @GetMapping("/entry-points")
    List<EntryPoint> entryPoints(@RequestHeader(COMPANY) UUID companyId) {
        return entryPoints.list(companyId);
    }

    @GetMapping("/entry-points/{id}")
    EntryPoint entryPoint(@RequestHeader(COMPANY) UUID companyId, @PathVariable UUID id) {
        return entryPoints.get(companyId, id);
    }

    @PostMapping(value = "/entry-points", consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<EntryPoint> createEntryPoint(@RequestHeader(COMPANY) UUID companyId,
                                                @RequestBody EntryPointService.Config config,
                                                HttpServletRequest request) {
        access.requireManager(request, companyId);
        return ResponseEntity.status(201).body(entryPoints.create(companyId, config, access.actor(request)));
    }

    @PutMapping(value = "/entry-points/{id}", consumes = MediaType.APPLICATION_JSON_VALUE)
    EntryPoint updateEntryPoint(@RequestHeader(COMPANY) UUID companyId, @PathVariable UUID id,
                                @RequestBody EntryPointService.Config config, HttpServletRequest request) {
        access.requireManager(request, companyId);
        return entryPoints.update(companyId, id, config, access.actor(request));
    }

    // ---------- listeners ----------

    /**
     * A listener as shown: {@code state} is the observed state (a stale heartbeat reads DEGRADED), {@code endpoint}
     * the path partners post to. The API key itself is only in the answer to a creation or a rotation.
     */
    record ListenerView(UUID id, String name, Listener.Kind kind, UUID channelId, UUID entryPointId,
                        Map<String, Object> settings, boolean enabled, Listener.DesiredState desiredState,
                        Listener.RuntimeState state, Listener.RuntimeState recordedState, String runtimeInstance,
                        OffsetDateTime lastHeartbeatAt, Map<String, Object> lastError, OffsetDateTime startedAt,
                        OffsetDateTime stoppedAt, OffsetDateTime lastActivityAt, long receivedCount,
                        long rejectedCount, String apiKeyHint, String endpoint, OffsetDateTime createdAt,
                        OffsetDateTime updatedAt, String apiKey) {
    }

    record ListenerRequest(String name, Listener.Kind kind, UUID channelId, UUID entryPointId,
                           Map<String, Object> settings, Boolean enabled) {
    }

    @GetMapping("/listeners")
    List<ListenerView> listeners(@RequestHeader(COMPANY) UUID companyId) {
        return listeners.list(companyId).stream().map(l -> view(l, null)).toList();
    }

    @GetMapping("/listeners/{id}")
    ListenerView listener(@RequestHeader(COMPANY) UUID companyId, @PathVariable UUID id) {
        return view(listeners.get(companyId, id), null);
    }

    @PostMapping(value = "/listeners", consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<ListenerView> createListener(@RequestHeader(COMPANY) UUID companyId,
                                                @RequestBody ListenerRequest r, HttpServletRequest request) {
        access.requireManager(request, companyId);
        ListenerService.Created created = listeners.create(companyId, new ListenerService.Config(r.name(), r.kind(),
                r.channelId(), r.entryPointId(), r.settings(), r.enabled()), access.actor(request));
        return ResponseEntity.status(201).body(view(created.listener(), created.apiKey()));
    }

    @PatchMapping(value = "/listeners/{id}", consumes = MediaType.APPLICATION_JSON_VALUE)
    ListenerView updateListener(@RequestHeader(COMPANY) UUID companyId, @PathVariable UUID id,
                                @RequestBody ListenerRequest r, HttpServletRequest request) {
        access.requireManager(request, companyId);
        return view(listeners.update(companyId, id, r.name(), r.settings(), r.enabled(), access.actor(request)), null);
    }

    @PostMapping("/listeners/{id}/api-key")
    ListenerView rotateKey(@RequestHeader(COMPANY) UUID companyId, @PathVariable UUID id, HttpServletRequest request) {
        access.requireManager(request, companyId);
        ListenerService.Created rotated = listeners.rotateKey(companyId, id, access.actor(request));
        return view(rotated.listener(), rotated.apiKey());
    }

    /** Answers once the listener runs (200), or with the structured reason it does not. */
    @PostMapping("/listeners/{id}/start")
    ListenerView start(@RequestHeader(COMPANY) UUID companyId, @PathVariable UUID id, HttpServletRequest request) {
        access.requireManager(request, companyId);
        return view(listeners.start(companyId, id, access.actor(request)), null);
    }

    @PostMapping("/listeners/{id}/stop")
    ListenerView stop(@RequestHeader(COMPANY) UUID companyId, @PathVariable UUID id, HttpServletRequest request) {
        access.requireManager(request, companyId);
        return view(listeners.stop(companyId, id, access.actor(request)), null);
    }

    @GetMapping("/listeners/{id}/events")
    List<ListenerStore.Event> events(@RequestHeader(COMPANY) UUID companyId, @PathVariable UUID id,
                                     @RequestParam(defaultValue = "100") int limit) {
        return listeners.events(companyId, id, limit);
    }

    private ListenerView view(Listener l, String apiKey) {
        String endpoint = l.kind() == Listener.Kind.HTTP_INBOUND ? HttpInboundController.PATH + "/" + l.id() : null;
        return new ListenerView(l.id(), l.name(), l.kind(), l.channelId(), l.entryPointId(), l.settings(), l.enabled(),
                l.desiredState(), listeners.observedState(l), l.runtimeState(), l.runtimeInstance(),
                l.lastHeartbeatAt(), l.lastError(), l.startedAt(), l.stoppedAt(), l.lastActivityAt(),
                l.receivedCount(), l.rejectedCount(), l.apiKeyHint(), endpoint, l.createdAt(), l.updatedAt(), apiKey);
    }
}
