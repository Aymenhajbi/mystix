package ma.mystix.referential;

import java.util.List;
import java.util.Map;
import jakarta.servlet.http.HttpServletRequest;
import ma.mystix.auth.Access;
import ma.mystix.shared.error.ErrorCode;
import ma.mystix.shared.error.MystixException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Barcode lookup in the product reference: any signed-in user (portal, Atelys Mobile). */
@RestController
@RequestMapping(value = "/api/v1/referential/products", produces = MediaType.APPLICATION_JSON_VALUE)
class ProductReferenceController {

    private final ProductReference reference;

    ProductReferenceController(ProductReference reference) {
        this.reference = reference;
    }

    record Status(Map<String, Long> counts, List<ProductReference.Import> imports, String licence) {
    }

    @GetMapping("/status")
    Status status() {
        return new Status(reference.counts(), reference.imports(), ProductReference.LICENCE);
    }

    @GetMapping("/{code}")
    ProductReference.Lookup lookup(@PathVariable String code) {
        return reference.lookup(code);
    }
}

/** Operator: (re)import one open database from the server directory. Off unless {@code mystix.admin.enabled=true}. */
@RestController
@RequestMapping(value = "/api/v1/admin/product-references", produces = MediaType.APPLICATION_JSON_VALUE)
@ConditionalOnProperty(name = "mystix.admin.enabled", havingValue = "true")
class ProductReferenceAdminController {

    private final ProductReference reference;
    private final Access access;

    ProductReferenceAdminController(ProductReference reference, Access access) {
        this.reference = reference;
        this.access = access;
    }

    @PostMapping("/{source}/import")
    ResponseEntity<ProductReference.Import> start(@PathVariable String source, HttpServletRequest http) {
        ProductReference.Source s;
        try {
            s = ProductReference.Source.valueOf(source.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new MystixException(ErrorCode.VALIDATION_FAILED, "Source must be OFF, OBF or OPF");
        }
        return ResponseEntity.accepted().body(reference.start(s, access.actor(http)));
    }
}
