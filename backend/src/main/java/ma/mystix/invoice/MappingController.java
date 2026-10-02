package ma.mystix.invoice;

import java.util.List;

import ma.mystix.format.ubl.UblMappingSpec;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Read-only description of the mappings implemented in the code. Not tenant data: no company header. */
@RestController
@RequestMapping("/api/v1/mappings")
class MappingController {

    record MappingView(String id, String version, String source, String target, List<UblMappingSpec.Field> fields) {
    }

    @GetMapping(value = "/" + UblMappingSpec.ID, produces = MediaType.APPLICATION_JSON_VALUE)
    MappingView ublInvoice() {
        return new MappingView(UblMappingSpec.ID, UblMappingSpec.VERSION, "JSON /api/v1/invoices → canonique 1.0",
                "UBL 2.1 Invoice (EN 16931)", UblMappingSpec.FIELDS);
    }
}
