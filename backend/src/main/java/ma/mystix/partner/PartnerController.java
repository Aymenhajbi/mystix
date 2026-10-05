package ma.mystix.partner;

import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Partners of the client environment named by {@code X-Mystix-Company-Id}.
 * TODO(auth): company from the authenticated principal instead of the header (Lot 8).
 */
@RestController
@RequestMapping("/api/v1/partners")
class PartnerController {

    private static final String COMPANY = "X-Mystix-Company-Id";

    private final PartnerService partners;

    PartnerController(PartnerService partners) {
        this.partners = partners;
    }

    record CreatePartnerRequest(@NotBlank @Size(max = 160) String name, @NotNull Partner.Type type,
                                @Size(max = 15) String ice, @Size(max = 13) String gln,
                                @Size(max = 60) String reference) {
    }

    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    List<Partner> list(@RequestHeader(COMPANY) UUID companyId) {
        return partners.list(companyId);
    }

    @GetMapping(value = "/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    Partner get(@RequestHeader(COMPANY) UUID companyId, @PathVariable UUID id) {
        return partners.get(companyId, id);
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<Partner> create(@RequestHeader(COMPANY) UUID companyId,
                                   @Valid @RequestBody CreatePartnerRequest r) {
        return ResponseEntity.status(201)
                .body(partners.create(companyId, r.name(), r.type(), r.ice(), r.gln(), r.reference()));
    }
}
