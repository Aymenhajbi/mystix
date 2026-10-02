package ma.mystix.tenant;

import java.net.URI;
import java.time.OffsetDateTime;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/companies")
class CompanyController {

    private final CompanyService service;

    CompanyController(CompanyService service) {
        this.service = service;
    }

    record CreateCompanyRequest(
            @NotBlank @Pattern(regexp = "^[0-9]{15}$", message = "must be exactly 15 digits") String ice,
            @NotBlank @Size(max = 255) String legalName,
            @Size(max = 20) String taxIdentifier) {
    }

    record CompanyResponse(UUID id, String ice, String legalName, String taxIdentifier, OffsetDateTime createdAt) {

        static CompanyResponse from(Company c) {
            return new CompanyResponse(c.id(), c.ice().value(), c.legalName(), c.taxIdentifier(), c.createdAt());
        }
    }

    @PostMapping
    ResponseEntity<CompanyResponse> create(@Valid @RequestBody CreateCompanyRequest request) {
        Company company = service.register(new Ice(request.ice()), request.legalName(), request.taxIdentifier());
        return ResponseEntity.created(URI.create("/api/v1/companies/" + company.id()))
                .body(CompanyResponse.from(company));
    }

    @GetMapping("/{id}")
    CompanyResponse get(@PathVariable UUID id) {
        return CompanyResponse.from(service.get(id));
    }
}
