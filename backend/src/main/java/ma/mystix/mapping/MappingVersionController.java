package ma.mystix.mapping;

import java.util.List;
import java.util.UUID;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Mapping versions of a flow (ADR-0008).
 * TODO(auth): company from the authenticated principal instead of the header (Lot 8).
 */
@RestController
@RequestMapping("/api/v1/flows/{flowId}/mapping")
class MappingVersionController {

    private static final String COMPANY = "X-Mystix-Company-Id";

    private final MappingVersionService versions;

    MappingVersionController(MappingVersionService versions) {
        this.versions = versions;
    }

    record CatalogView(List<RuleCatalog.Field> targets, List<RuleCatalog.Field> sources, List<String> ops) {
    }

    record MappingView(CatalogView catalog, List<MappingVersion> versions) {
    }

    record RulesBody(List<MappingRule> rules) {
    }

    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    MappingView get(@RequestHeader(COMPANY) UUID companyId, @PathVariable UUID flowId) {
        return new MappingView(
                new CatalogView(RuleCatalog.TARGETS, RuleCatalog.SOURCES, RuleCatalog.OPS.stream().sorted().toList()),
                versions.versions(companyId, flowId));
    }

    @PostMapping(value = "/versions", produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<MappingVersion> create(@RequestHeader(COMPANY) UUID companyId, @PathVariable UUID flowId,
                                          @RequestBody(required = false) RulesBody body) {
        return ResponseEntity.status(201)
                .body(versions.createDraft(companyId, flowId, body == null ? null : body.rules()));
    }

    @PutMapping(value = "/versions/{version}", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    MappingVersion update(@RequestHeader(COMPANY) UUID companyId, @PathVariable UUID flowId,
                          @PathVariable int version, @RequestBody RulesBody body) {
        return versions.updateDraft(companyId, flowId, version, body.rules());
    }

    @PostMapping(value = "/versions/{version}/test", produces = MediaType.APPLICATION_JSON_VALUE)
    MappingVersion test(@RequestHeader(COMPANY) UUID companyId, @PathVariable UUID flowId, @PathVariable int version) {
        return versions.test(companyId, flowId, version);
    }

    @PostMapping(value = "/versions/{version}/publish", produces = MediaType.APPLICATION_JSON_VALUE)
    MappingVersion publish(@RequestHeader(COMPANY) UUID companyId, @PathVariable UUID flowId,
                           @PathVariable int version) {
        return versions.publish(companyId, flowId, version);
    }
}
