package ma.mystix.connector;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import ma.mystix.flow.ExchangeFlow;
import ma.mystix.shared.error.ApiError;
import org.springframework.stereotype.Component;

/**
 * HTTP inbound listener (ADR-0016): {@code POST /inbound/http/{listenerId}} on the Mystix server. Started, the route
 * is in this process's table and {@link HttpInboundController} accepts on it; stopped, it is gone and the controller
 * answers 503. Nothing else decides whether a message is accepted.
 */
@Component
class HttpInboundAdapter implements ListenerAdapter {

    static final int DEFAULT_MAX_BYTES = 5 * 1024 * 1024;
    static final int MAX_MAX_BYTES = 20 * 1024 * 1024;

    /** What the controller needs to accept a message on a started listener. */
    record Route(UUID listenerId, UUID companyId, UUID flowId, String apiKeyHash, int maxBytes) {
    }

    private final Map<UUID, Route> routes = new ConcurrentHashMap<>();

    @Override
    public Listener.Kind kind() {
        return Listener.Kind.HTTP_INBOUND;
    }

    @Override
    public List<ApiError.FieldViolation> preconditions(Context c) {
        List<ApiError.FieldViolation> problems = new ArrayList<>();
        Channel channel = c.channel();
        if (!Set.of(Channel.Protocol.HTTPS, Channel.Protocol.WEBHOOK).contains(channel.protocol())) {
            problems.add(new ApiError.FieldViolation("channel.protocol", "HTTPS or WEBHOOK required to receive over HTTP"));
        }
        if (channel.direction() == Channel.Direction.OUT) {
            problems.add(new ApiError.FieldViolation("channel.direction", "IN or BOTH required to receive"));
        }
        if (channel.status() != Channel.Status.ACTIVE) {
            problems.add(new ApiError.FieldViolation("channel.status", "the channel must be ACTIVE"));
        }
        EntryPoint entry = c.entryPoint();
        if (!entry.enabled()) {
            problems.add(new ApiError.FieldViolation("entryPoint.enabled", "the entry point is disabled"));
        }
        if (!entry.dispatchable()) {
            problems.add(new ApiError.FieldViolation("entryPoint.inputFormat", entry.documentType() + " in "
                    + entry.inputFormat() + " cannot be handed to the pipeline yet; JSON invoices can"));
        }
        if (c.flow().status() != ExchangeFlow.Status.ACTIVE) {
            problems.add(new ApiError.FieldViolation("entryPoint.flow", "the flow " + c.flow().name() + " is not ACTIVE"));
        }
        if (c.apiKeyHash() == null) {
            problems.add(new ApiError.FieldViolation("apiKey", "no API key: generate one first"));
        }
        maxBytes(c.listener().settings(), problems);
        return problems;
    }

    @Override
    public Handle start(Context c) {
        Route route = new Route(c.listener().id(), c.listener().companyId(), c.flow().id(), c.apiKeyHash(),
                maxBytes(c.listener().settings(), new ArrayList<>()));
        routes.put(route.listenerId(), route);
        return new Handle(kind(), route.listenerId(), route.companyId(), route);
    }

    @Override
    public void stop(Handle handle) {
        routes.remove(handle.listenerId());
    }

    @Override
    public Health health(Handle handle) {
        Route route = routes.get(handle.listenerId());
        if (route == null || route != handle.resource()) {
            return new Health(false, "ROUTE_MISSING", "the route is not registered in this process");
        }
        return Health.ok();
    }

    /** The route of a started listener, if this process serves it. */
    Optional<Route> route(UUID listenerId) {
        return Optional.ofNullable(routes.get(listenerId));
    }

    static int maxBytes(Map<String, Object> settings, List<ApiError.FieldViolation> problems) {
        Object value = settings == null ? null : settings.get("maxBytes");
        if (value == null) {
            return DEFAULT_MAX_BYTES;
        }
        if (value instanceof Number n && n.longValue() >= 1 && n.longValue() <= MAX_MAX_BYTES) {
            return n.intValue();
        }
        problems.add(new ApiError.FieldViolation("settings.maxBytes", "1 to " + MAX_MAX_BYTES + " bytes"));
        return DEFAULT_MAX_BYTES;
    }
}
