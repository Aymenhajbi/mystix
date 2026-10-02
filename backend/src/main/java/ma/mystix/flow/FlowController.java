package ma.mystix.flow;

import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
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

    record CatalogView(List<FlowCatalog.Option> sourceChannels, List<FlowCatalog.Option> sourceFormats,
                       List<FlowCatalog.Option> targetFormats, List<FlowCatalog.Option> targetChannels,
                       List<FlowCatalog.Mapping> mappings) {
    }

    record CreateFlowRequest(@NotBlank @Size(max = 120) String name,
                             @NotBlank String sourceChannel, @NotBlank String sourceFormat,
                             @NotBlank String targetFormat, @NotBlank String targetChannel) {
    }

    record UpdateFlowRequest(@Size(max = 120) String name, ExchangeFlow.Status status) {
    }

    @GetMapping(value = "/catalog", produces = MediaType.APPLICATION_JSON_VALUE)
    CatalogView catalog() {
        return new CatalogView(FlowCatalog.SOURCE_CHANNELS, FlowCatalog.SOURCE_FORMATS, FlowCatalog.TARGET_FORMATS,
                FlowCatalog.TARGET_CHANNELS, FlowCatalog.MAPPINGS);
    }

    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    List<ExchangeFlow> list(@RequestHeader(COMPANY) UUID companyId) {
        return flows.list(companyId);
    }

    @GetMapping(value = "/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    ExchangeFlow get(@RequestHeader(COMPANY) UUID companyId, @PathVariable UUID id) {
        return flows.get(companyId, id);
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<ExchangeFlow> create(@RequestHeader(COMPANY) UUID companyId,
                                        @Valid @RequestBody CreateFlowRequest request) {
        ExchangeFlow flow = flows.create(companyId, new FlowService.NewFlow(request.name(), request.sourceChannel(),
                request.sourceFormat(), request.targetFormat(), request.targetChannel()));
        return ResponseEntity.status(201).body(flow);
    }

    @PatchMapping(value = "/{id}", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    ExchangeFlow update(@RequestHeader(COMPANY) UUID companyId, @PathVariable UUID id,
                        @Valid @RequestBody UpdateFlowRequest request) {
        return flows.update(companyId, id, request.name(), request.status());
    }
}
