package ma.mystix.invoice;

import java.util.LinkedHashMap;
import java.util.Map;

import ma.mystix.canonical.CanonicalVersion;
import ma.mystix.canonical.Invoice;
import tools.jackson.core.StreamWriteFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * Deterministic JSON serialization of the canonical invoice: fixed property order, plain decimals.
 * Its SHA-256 identifies the invoice content for idempotent replays.
 */
final class CanonicalJson {

    private static final JsonMapper MAPPER = JsonMapper.builder()
            .enable(StreamWriteFeature.WRITE_BIGDECIMAL_AS_PLAIN)
            .build();

    private CanonicalJson() {
    }

    static byte[] write(Invoice invoice) {
        Map<String, Object> document = new LinkedHashMap<>();
        document.put("canonicalVersion", CanonicalVersion.CURRENT);
        document.put("invoice", invoice);
        return MAPPER.writeValueAsBytes(document);
    }
}
