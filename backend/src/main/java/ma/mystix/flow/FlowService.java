package ma.mystix.flow;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import ma.mystix.shared.error.ErrorCode;
import ma.mystix.shared.error.MystixException;
import ma.mystix.partner.PartnerService;
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
    private final PartnerService partners;
    private final Clock clock;

    FlowService(FlowRepository repository, CompanyService companies, PartnerService partners, Clock clock) {
        this.repository = repository;
        this.companies = companies;
        this.partners = partners;
        this.clock = clock;
    }

    /**
     * What to create; codes come from {@link FlowCatalog} for the direction.
     *
     * @param direction OUT (the client sends) or IN (the client receives)
     * @param partnerId one partner of the client, or {@code null} for all partners
     */
    public record NewFlow(String name, String direction, UUID partnerId, String sourceChannel, String sourceFormat,
                          String targetFormat, String targetChannel) {
    }

    /** Every new client environment starts with the flow Mystix already runs: invoices by API to UBL 2.1. */
    @EventListener
    void provision(CompanyRegistered event) {
        OffsetDateTime now = OffsetDateTime.now(clock);
        repository.insert(new ExchangeFlow(UUID.randomUUID(), event.company().id(), ExchangeFlow.DEFAULT_NAME,
                "INVOICE", "OUT", "API", "JSON_CANONICAL", "UBL_2_1", "API_RESPONSE", "ubl-invoice", "1.0",
                ExchangeFlow.Status.ACTIVE, now, now, null));
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

    /**
     * New flows start as drafts. Options must exist in the direction catalog; planned options are accepted so a
     * flow can be declared now, but such a flow cannot be activated until it is executable.
     */
    @Transactional
    public ExchangeFlow create(UUID companyId, NewFlow request) {
        companies.get(companyId);
        FlowCatalog.DirectionCatalog catalog = FlowCatalog.of(request.direction());
        if (catalog == null
                || FlowCatalog.option(catalog.sourceChannels(), request.sourceChannel()).isEmpty()
                || FlowCatalog.option(catalog.sourceFormats(), request.sourceFormat()).isEmpty()
                || FlowCatalog.option(catalog.targetFormats(), request.targetFormat()).isEmpty()
                || FlowCatalog.option(catalog.targetChannels(), request.targetChannel()).isEmpty()) {
            throw new MystixException(ErrorCode.FLOW_OPTION_UNAVAILABLE, "Unknown option in " + request);
        }
        if (request.partnerId() != null) {
            partners.get(companyId, request.partnerId());
        }
        var mapping = FlowCatalog.mappingFor(request.direction(), request.sourceFormat(), request.targetFormat());
        OffsetDateTime now = OffsetDateTime.now(clock);
        ExchangeFlow flow = new ExchangeFlow(UUID.randomUUID(), companyId, request.name().strip(), "INVOICE",
                request.direction(), request.sourceChannel(), request.sourceFormat(), request.targetFormat(),
                request.targetChannel(), mapping.map(FlowCatalog.Mapping::id).orElse(null),
                mapping.map(FlowCatalog.Mapping::version).orElse(null), ExchangeFlow.Status.DRAFT, now, now,
                request.partnerId());
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
        if (newStatus == ExchangeFlow.Status.ACTIVE && !FlowCatalog.executable(current)) {
            throw new MystixException(ErrorCode.FLOW_NOT_EXECUTABLE, "Flow " + flowId + " is declared only");
        }
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
        companies.get(companyId);
        if (requestedFlowId != null) {
            ExchangeFlow flow = get(companyId, requestedFlowId);
            if (flow.status() != ExchangeFlow.Status.ACTIVE || !"API".equals(flow.sourceChannel())
                    || !"OUT".equals(flow.direction())) {
                throw new MystixException(ErrorCode.FLOW_NOT_ACTIVE, "Flow " + requestedFlowId + " is " + flow.status());
            }
            return flow;
        }
        return repository.defaultApiFlow(companyId)
                .orElseThrow(() -> new MystixException(ErrorCode.FLOW_NOT_ACTIVE,
                        "No active API invoice flow for company " + companyId));
    }
}
