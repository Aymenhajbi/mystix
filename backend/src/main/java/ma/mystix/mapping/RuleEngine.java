package ma.mystix.mapping;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Validates and applies flow rules (ADR-0008) on a flat view of a request: header fields and one map per line.
 * Pure Java, deterministic: same view and rules, same result.
 */
public final class RuleEngine {

    /** Flat, mutable copy of the editable part of a request. Missing or blank values are {@code null}. */
    public record View(Map<String, String> header, List<Map<String, String>> lines) {

        public View copy() {
            List<Map<String, String>> copiedLines = new ArrayList<>();
            lines.forEach(l -> copiedLines.add(new LinkedHashMap<>(l)));
            return new View(new LinkedHashMap<>(header), copiedLines);
        }
    }

    /** A rule refused a value at run time (lookup with REJECT fallback). */
    public static final class Rejection extends RuntimeException {

        private final String field;

        Rejection(String field, String message) {
            super(message);
            this.field = field;
        }

        public String field() {
            return field;
        }
    }

    /** One problem in a rule set, with the path of the offending element. */
    public record Violation(String path, String reason) {
    }

    private RuleEngine() {
    }

    public static List<Violation> validate(List<MappingRule> rules) {
        List<Violation> v = new ArrayList<>();
        if (rules == null) {
            return List.of(new Violation("rules", "is required"));
        }
        if (rules.size() > RuleCatalog.MAX_RULES) {
            v.add(new Violation("rules", "at most " + RuleCatalog.MAX_RULES + " rules"));
        }
        Set<String> targets = new HashSet<>();
        for (int i = 0; i < rules.size(); i++) {
            String at = "rules[" + i + "]";
            MappingRule r = rules.get(i);
            RuleCatalog.Field target = r == null ? null : RuleCatalog.target(r.target());
            if (target == null) {
                v.add(new Violation(at + ".target", "is not an editable field"));
                continue;
            }
            if (!targets.add(r.target())) {
                v.add(new Violation(at + ".target", "has more than one rule"));
            }
            if (r.source() != null) {
                MappingRule.Source s = r.source();
                if ("FIELD".equals(s.type())) {
                    RuleCatalog.Field source = RuleCatalog.source(s.path());
                    if (source == null) {
                        v.add(new Violation(at + ".source.path", "is not a readable field"));
                    } else if (source.scope() == RuleCatalog.Scope.LINE && target.scope() == RuleCatalog.Scope.HEADER) {
                        v.add(new Violation(at + ".source.path", "a line field cannot feed a header field"));
                    }
                } else if ("CONSTANT".equals(s.type())) {
                    if (s.value() == null || s.value().length() > RuleCatalog.MAX_TEXT) {
                        v.add(new Violation(at + ".source.value", "must be a text of at most " + RuleCatalog.MAX_TEXT));
                    }
                } else {
                    v.add(new Violation(at + ".source.type", "must be FIELD or CONSTANT"));
                }
            }
            if (r.transforms().size() > RuleCatalog.MAX_TRANSFORMS) {
                v.add(new Violation(at + ".transforms", "at most " + RuleCatalog.MAX_TRANSFORMS));
            }
            for (int j = 0; j < r.transforms().size(); j++) {
                String tAt = at + ".transforms[" + j + "]";
                MappingRule.Transform t = r.transforms().get(j);
                if (t == null || !RuleCatalog.OPS.contains(t.op())) {
                    v.add(new Violation(tAt + ".op", "must be one of " + RuleCatalog.OPS));
                    continue;
                }
                switch (t.op()) {
                    case "prefix", "suffix", "default" -> {
                        if (t.value() == null || t.value().isEmpty() || t.value().length() > RuleCatalog.MAX_TEXT) {
                            v.add(new Violation(tAt + ".value", "must be a text of 1 to " + RuleCatalog.MAX_TEXT));
                        }
                    }
                    case "lookup" -> {
                        if (t.table().isEmpty() || t.table().size() > RuleCatalog.MAX_LOOKUP_ENTRIES) {
                            v.add(new Violation(tAt + ".table", "must have 1 to " + RuleCatalog.MAX_LOOKUP_ENTRIES + " entries"));
                        }
                        if (t.fallback() != null && !Set.of("KEEP", "REJECT").contains(t.fallback())) {
                            v.add(new Violation(tAt + ".fallback", "must be KEEP or REJECT"));
                        }
                    }
                    default -> {
                    }
                }
            }
        }
        return List.copyOf(v);
    }

    /** Applies valid rules to a copy of the view. Call {@link #validate} first. */
    public static View apply(View input, List<MappingRule> rules) {
        View out = input.copy();
        for (MappingRule rule : rules) {
            RuleCatalog.Field target = RuleCatalog.target(rule.target());
            if (target.scope() == RuleCatalog.Scope.HEADER) {
                String value = sourceValue(rule, input.header(), null, target.path());
                out.header().put(target.path(), transform(rule, value, target.path()));
            } else {
                String key = target.path().substring("lines.".length());
                for (int i = 0; i < out.lines().size(); i++) {
                    Map<String, String> line = input.lines().get(i);
                    String value = sourceValue(rule, input.header(), line, target.path());
                    out.lines().get(i).put(key, transform(rule, value, "lines[" + i + "]." + key));
                }
            }
        }
        return out;
    }

    private static String sourceValue(MappingRule rule, Map<String, String> header, Map<String, String> line,
                                      String targetPath) {
        MappingRule.Source s = rule.source();
        if (s == null) {
            return read(header, line, targetPath);
        }
        return "CONSTANT".equals(s.type()) ? s.value() : read(header, line, s.path());
    }

    private static String read(Map<String, String> header, Map<String, String> line, String path) {
        if (path.startsWith("lines.")) {
            return line == null ? null : line.get(path.substring("lines.".length()));
        }
        return header.get(path);
    }

    private static String transform(MappingRule rule, String input, String field) {
        String value = input;
        for (MappingRule.Transform t : rule.transforms()) {
            value = switch (t.op()) {
                case "trim" -> value == null ? null : value.strip();
                case "upper" -> value == null ? null : value.toUpperCase(Locale.ROOT);
                case "lower" -> value == null ? null : value.toLowerCase(Locale.ROOT);
                case "prefix" -> value == null ? null : t.value() + value;
                case "suffix" -> value == null ? null : value + t.value();
                case "default" -> value == null || value.isBlank() ? t.value() : value;
                case "lookup" -> lookup(t, value, field);
                default -> value;
            };
        }
        return value == null || value.isBlank() ? null : value;
    }

    private static String lookup(MappingRule.Transform t, String value, String field) {
        if (value != null && t.table().containsKey(value)) {
            return t.table().get(value);
        }
        if ("REJECT".equals(t.fallback())) {
            throw new Rejection(field, "No lookup entry for '" + value + "'");
        }
        return value;
    }
}
