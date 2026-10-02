package ma.mystix.flow;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import ma.mystix.shared.error.ErrorCode;
import ma.mystix.shared.error.MystixException;
import ma.mystix.tenant.CompanyRegistered;
import ma.mystix.tenant.CompanyService;
import org.springframework.context.event.EventListener;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Exchange flows of each client environment. */
@Service
public class FlowService {

    private final FlowRepository repository;
    private final CompanyService companies;
    private final Clock clock;

    FlowService(FlowRepository repository, CompanyService companies, Clock clock) {
        this.repository = repository;
        this.companies = companies;
        this.clock = clock;
    }

    /** What to create; codes come from {@link FlowCatalog}. */
    public record NewFlow(String name, String sourceChannel, String sourceFormat, String targetFormat,
                          String targetChannel) {
    }

    /** Every new client environment starts with the flow Mystix already runs: invoices by API to UBL 2.1. */
    @EventListener
    void provision(CompanyRegistered event) {
        OffsetDateTime now = OffsetDateTime.now(clock);
        repository.insert(new ExchangeFlow(UUID.randomUUID(), event.company().id(), ExchangeFlow.DEFAULT_NAME,
                "INVOICE", "OUT", "API", "JSON_CANONICAL", "UBL_2_1", "API_RESPONSE", "ubl-invoice", "1.0",
                ExchangeFlow.Status.ACTIVE, now, now));
    }

    @Transactional(readOnly = true)
    public List<ExchangeFlow> list(UUID companyId) {
        companies.get(companyId);
        return repository.list(companyId);
    }

    @Transactional(readOnly = true)
    public ExchangeFlow get(UUID companyId, UUID flowId) {
        return repository.find(companyId, flowId)
                .orElseThrow(() -> new MystixException(ErrorCode.FLOW_NOT_FOUND,
                        "No flow " + flowId + " for company " + companyId));
    }

    /** New flows start as drafts; only available catalog options are accepted. */
    @Transactional
    public ExchangeFlow create(UUID companyId, NewFlow request) {
        companies.get(companyId);
        if (!FlowCatalog.isAvailable(FlowCatalog.SOURCE_CHANNELS, request.sourceChannel())
                || !FlowCatalog.isAvailable(FlowCatalog.SOURCE_FORMATS, request.sourceFormat())
                || !FlowCatalog.isAvailable(FlowCatalog.TARGET_FORMATS, request.targetFormat())
                || !FlowCatalog.isAvailable(FlowCatalog.TARGET_CHANNELS, request.targetChannel())) {
            throw new MystixException(ErrorCode.FLOW_OPTION_UNAVAILABLE, "Unavailable option in " + request);
        }
        FlowCatalog.Mapping mapping = FlowCatalog.mappingFor(request.sourceFormat(), request.targetFormat())
                .orElseThrow(() -> new MystixException(ErrorCode.FLOW_OPTION_UNAVAILABLE,
                        "No mapping from " + request.sourceFormat() + " to " + request.targetFormat()));
        OffsetDateTime now = OffsetDateTime.now(clock);
        ExchangeFlow flow = new ExchangeFlow(UUID.randomUUID(), companyId, request.name().strip(), "INVOICE", "OUT",
                request.sourceChannel(), request.sourceFormat(), request.targetFormat(), request.targetChannel(),
                mapping.id(), mapping.version(), ExchangeFlow.Status.DRAFT, now, now);
        try {
            repository.insert(flow);
        } catch (DuplicateKeyException e) {
            throw new MystixException(ErrorCode.FLOW_NAME_TAKEN, "Flow name taken: " + flow.name(), e);
        }
        return flow;
    }

    /** Renames a flow or changes its status. A paused or draft flow receives nothing. */
    @Transactional
    public ExchangeFlow update(UUID companyId, UUID flowId, String name, ExchangeFlow.Status status) {
        ExchangeFlow current = get(companyId, flowId);
        String newName = name == null || name.isBlank() ? current.name() : name.strip();
        ExchangeFlow.Status newStatus = status == null ? current.status() : status;
        try {
            repository.update(companyId, flowId, newName, newStatus, OffsetDateTime.now(clock));
        } catch (DuplicateKeyException e) {
            throw new MystixException(ErrorCode.FLOW_NAME_TAKEN, "Flow name taken: " + newName, e);
        }
        return get(companyId, flowId);
    }

    /**
     * The flow an API submission enters: the one named by the caller, or the company's first active API invoice
     * flow. A named flow must exist, belong to the company and be active.
     */
    @Transactional(readOnly = true)
    public ExchangeFlow forSubmission(UUID companyId, UUID requestedFlowId) {
        if (requestedFlowId != null) {
            ExchangeFlow flow = get(companyId, requestedFlowId);
            if (flow.status() != ExchangeFlow.Status.ACTIVE || !"API".equals(flow.sourceChannel())) {
                throw new MystixException(ErrorCode.FLOW_NOT_ACTIVE, "Flow " + requestedFlowId + " is " + flow.status());
            }
            return flow;
        }
        return repository.defaultApiFlow(companyId)
                .orElseThrow(() -> new MystixException(ErrorCode.FLOW_NOT_ACTIVE,
                        "No active API invoice flow for company " + companyId));
    }
}
