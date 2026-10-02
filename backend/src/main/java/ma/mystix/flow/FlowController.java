package ma.mystix.flow;

import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonUnwrapped;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Exchange flows of the client environment named by {@code X-Mystix-Company-Id}.
 * TODO(auth): company from the authenticated principal instead of the header (Lot 8).
 */
@RestController
@RequestMapping("/api/v1/flows")
class FlowController {

    private static final String COMPANY = "X-Mystix-Company-Id";

    private final FlowService flows;

    FlowController(FlowService flows) {
        this.flows = flows;
    }

    record CatalogView(FlowCatalog.DirectionCatalog out, FlowCatalog.DirectionCatalog in,
                       List<FlowCatalog.Mapping> mappings) {
    }

    record CreateFlowRequest(@NotBlank @Size(max = 120) String name,
                             @NotBlank @Pattern(regexp = "^(IN|OUT)$") String direction, UUID partnerId,
                             @NotBlank String sourceChannel, @NotBlank String sourceFormat,
                             @NotBlank String targetFormat, @NotBlank String targetChannel) {
    }

    /** A flow with whether it can run today. */
    record FlowView(@JsonUnwrapped ExchangeFlow flow, boolean executable) {
    }

    record UpdateFlowRequest(@Size(max = 120) String name, ExchangeFlow.Status status) {
    }

    @GetMapping(value = "/catalog", produces = MediaType.APPLICATION_JSON_VALUE)
    CatalogView catalog() {
        return new CatalogView(FlowCatalog.OUT, FlowCatalog.IN, FlowCatalog.MAPPINGS);
    }

    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    List<FlowView> list(@RequestHeader(COMPANY) UUID companyId) {
        return flows.list(companyId).stream().map(FlowController::view).toList();
    }

    @GetMapping(value = "/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    FlowView get(@RequestHeader(COMPANY) UUID companyId, @PathVariable UUID id) {
        return view(flows.get(companyId, id));
    }

    private static FlowView view(ExchangeFlow flow) {
        return new FlowView(flow, FlowCatalog.executable(flow));
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<FlowView> create(@RequestHeader(COMPANY) UUID companyId,
                                        @Valid @RequestBody CreateFlowRequest request) {
        ExchangeFlow flow = flows.create(companyId, new FlowService.NewFlow(request.name(), request.direction(),
                request.partnerId(), request.sourceChannel(), request.sourceFormat(), request.targetFormat(),
                request.targetChannel()));
        return ResponseEntity.status(201).body(view(flow));
    }

    @PatchMapping(value = "/{id}", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    FlowView update(@RequestHeader(COMPANY) UUID companyId, @PathVariable UUID id,
                    @Valid @RequestBody UpdateFlowRequest request) {
        return view(flows.update(companyId, id, request.name(), request.status()));
    }
}
