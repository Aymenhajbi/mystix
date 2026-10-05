package ma.mystix.mapping;

import java.util.List;
import java.util.Map;

/**
 * One rule of a mapping version (ADR-0008): where the target value comes from, then a chain of transformations.
 *
 * @param target     field written, see {@link RuleCatalog#TARGETS}
 * @param source     where the value comes from; {@code null} keeps the target's own value
 * @param transforms applied in order
 */
public record MappingRule(String target, Source source, List<Transform> transforms) {

    public MappingRule {
        transforms = transforms == null ? List.of() : List.copyOf(transforms);
    }

    /**
     * @param type  FIELD (another field of the request) or CONSTANT
     * @param path  for FIELD, see {@link RuleCatalog#SOURCES}
     * @param value for CONSTANT
     */
    public record Source(String type, String path, String value) {
    }

    /**
     * @param op       trim, upper, lower, prefix, suffix, lookup, default
     * @param value    argument of prefix, suffix, default
     * @param table    lookup table, exact match
     * @param fallback lookup when no entry matches: KEEP (default) or REJECT
     */
    public record Transform(String op, String value, Map<String, String> table, String fallback) {

        public Transform {
            table = table == null ? Map.of() : Map.copyOf(table);
        }
    }
}
