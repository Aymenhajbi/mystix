package ma.mystix.referential;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;

import ma.mystix.shared.time.TimeConfig;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Read access to the dated VAT referential (platform data, the same for every client environment). */
@RestController
@RequestMapping("/api/v1/referential/vat-rates")
class VatRateController {

    private final VatReferential referential;
    private final Clock clock;

    VatRateController(VatReferential referential, Clock clock) {
        this.referential = referential;
        this.clock = clock;
    }

    /** Rates in force on {@code date} (default: today in Africa/Casablanca). */
    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    List<VatRate> inForce(@RequestParam(defaultValue = "MA") String country,
                          @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
                          LocalDate date) {
        LocalDate on = date != null ? date : LocalDate.now(clock.withZone(TimeConfig.BUSINESS_ZONE));
        return referential.inForce(country, on);
    }
}
