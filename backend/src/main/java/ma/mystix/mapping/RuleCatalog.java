package ma.mystix.mapping;

import java.util.List;
import java.util.Set;

/**
 * What flow rules may touch (ADR-0008). Only free-text and code fields that carry no fiscal meaning: never amounts,
 * VAT rates, ICE, IF, dates or invoice numbers.
 */
public final class RuleCatalog {

    public enum Scope { HEADER, LINE }

    /**
     * @param path   dotted path in the request JSON; {@code lines.x} is applied to every line
     * @param scope  header or line
     * @param term   EN 16931 business term
     * @param maxLen maximum length accepted by the request contract
     */
    public record Field(String path, Scope scope, String term, int maxLen) {
    }

    public static final List<Field> TARGETS = List.of(
            new Field("buyerReference", Scope.HEADER, "BT-10", 100),
            new Field("purchaseOrderReference", Scope.HEADER, "BT-13", 100),
            new Field("note", Scope.HEADER, "BT-22", 1000),
            new Field("seller.name", Scope.HEADER, "BT-27", 255),
            new Field("buyer.name", Scope.HEADER, "BT-44", 255),
            new Field("lines.itemName", Scope.LINE, "BT-153", 255),
            new Field("lines.unitCode", Scope.LINE, "BT-130", 3));

    /** Fields a rule may read. Line sources are read on the same line as the target. */
    public static final List<Field> SOURCES = List.of(
            new Field("number", Scope.HEADER, "BT-1", 100),
            new Field("buyerReference", Scope.HEADER, "BT-10", 100),
            new Field("purchaseOrderReference", Scope.HEADER, "BT-13", 100),
            new Field("note", Scope.HEADER, "BT-22", 1000),
            new Field("seller.name", Scope.HEADER, "BT-27", 255),
            new Field("buyer.name", Scope.HEADER, "BT-44", 255),
            new Field("lines.id", Scope.LINE, "BT-126", 50),
            new Field("lines.itemName", Scope.LINE, "BT-153", 255),
            new Field("lines.unitCode", Scope.LINE, "BT-130", 3),
            new Field("lines.gtin", Scope.LINE, "BT-157", 14));

    public static final Set<String> OPS = Set.of("trim", "upper", "lower", "prefix", "suffix", "lookup", "default");

    /** Limits that keep a version readable and cheap to run. */
    public static final int MAX_RULES = 50;
    public static final int MAX_TRANSFORMS = 10;
    public static final int MAX_LOOKUP_ENTRIES = 500;
    public static final int MAX_TEXT = 255;

    private RuleCatalog() {
    }

    static Field target(String path) {
        return TARGETS.stream().filter(f -> f.path().equals(path)).findFirst().orElse(null);
    }

    static Field source(String path) {
        return SOURCES.stream().filter(f -> f.path().equals(path)).findFirst().orElse(null);
    }
}
