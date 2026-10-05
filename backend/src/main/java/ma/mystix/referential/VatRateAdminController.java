package ma.mystix.referential;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Operator maintenance of the VAT referential. Off unless {@code mystix.admin.enabled=true}.
 * TODO(auth): restrict to the operator role (Lot 8).
 */
@RestController
@RequestMapping("/api/v1/admin/vat-rates")
@ConditionalOnProperty(name = "mystix.admin.enabled", havingValue = "true")
class VatRateAdminController {

    private final VatReferential referential;

    VatRateAdminController(VatReferential referential) {
        this.referential = referential;
    }

    record NewVatRate(String countryCode, String categoryCode, BigDecimal ratePercent, LocalDate validFrom,
                      LocalDate validTo, String legalReference) {
    }

    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    List<VatRate> all() {
        return referential.all();
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<VatRate> add(@RequestBody NewVatRate r) {
        return ResponseEntity.status(201).body(referential.add(r.countryCode(), r.categoryCode(), r.ratePercent(),
                r.validFrom(), r.validTo(), r.legalReference()));
    }
}
