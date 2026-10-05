package ma.mystix.mapping;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

class RuleEngineTest {

    private static RuleEngine.View view() {
        Map<String, String> header = new HashMap<>();
        header.put("number", "FA-1");
        header.put("purchaseOrderReference", "PO-7781");
        header.put("buyer.name", "  client test  ");
        Map<String, String> line1 = new HashMap<>(Map.of("id", "1", "unitCode", "PCE", "itemName", "Carton A"));
        Map<String, String> line2 = new HashMap<>(Map.of("id", "2", "unitCode", "KGM", "itemName", "Sac B"));
        return new RuleEngine.View(header, List.of(line1, line2));
    }

    private static MappingRule.Transform op(String op) {
        return new MappingRule.Transform(op, null, null, null);
    }

    @Test
    void lookupConvertsSapUnitsAndKeepsUnknownOnes() {
        MappingRule rule = new MappingRule("lines.unitCode", null,
                List.of(new MappingRule.Transform("lookup", null, Map.of("PCE", "C62"), "KEEP")));

        RuleEngine.View out = RuleEngine.apply(view(), List.of(rule));

        assertThat(out.lines()).extracting(l -> l.get("unitCode")).containsExactly("C62", "KGM");
    }

    @Test
    void lookupWithRejectRefusesUnknownValues() {
        MappingRule rule = new MappingRule("lines.unitCode", null,
                List.of(new MappingRule.Transform("lookup", null, Map.of("PCE", "C62"), "REJECT")));

        assertThatThrownBy(() -> RuleEngine.apply(view(), List.of(rule)))
                .isInstanceOf(RuleEngine.Rejection.class)
                .satisfies(e -> assertThat(((RuleEngine.Rejection) e).field()).isEqualTo("lines[1].unitCode"));
    }

    @Test
    void sourcesConstantsAndTextTransforms() {
        List<MappingRule> rules = List.of(
                new MappingRule("buyerReference", new MappingRule.Source("FIELD", "purchaseOrderReference", null),
                        List.of(new MappingRule.Transform("prefix", "REF-", null, null))),
                new MappingRule("buyer.name", null, List.of(op("trim"), op("upper"))),
                new MappingRule("note", new MappingRule.Source("CONSTANT", null, "Livraison franco"), List.of()),
                new MappingRule("lines.itemName", new MappingRule.Source("FIELD", "lines.id", null),
                        List.of(new MappingRule.Transform("prefix", "Article ", null, null))));

        RuleEngine.View in = view();
        RuleEngine.View out = RuleEngine.apply(in, rules);

        assertThat(out.header())
                .containsEntry("buyerReference", "REF-PO-7781")
                .containsEntry("buyer.name", "CLIENT TEST")
                .containsEntry("note", "Livraison franco");
        assertThat(out.lines()).extracting(l -> l.get("itemName")).containsExactly("Article 1", "Article 2");
        assertThat(in.header()).containsEntry("buyer.name", "  client test  ");
    }

    @Test
    void defaultOnlyFillsEmptyValues() {
        MappingRule rule = new MappingRule("buyerReference", null,
                List.of(new MappingRule.Transform("default", "SANS-REF", null, null)));

        assertThat(RuleEngine.apply(view(), List.of(rule)).header()).containsEntry("buyerReference", "SANS-REF");
    }

    @Test
    void refusesFiscalFieldsUnknownOpsAndLineSourcesOnHeaders() {
        List<RuleEngine.Violation> violations = RuleEngine.validate(List.of(
                new MappingRule("lines.unitPrice", null, List.of()),
                new MappingRule("seller.ice", null, List.of()),
                new MappingRule("note", new MappingRule.Source("FIELD", "lines.itemName", null), List.of()),
                new MappingRule("buyer.name", null, List.of(op("eval"))),
                new MappingRule("lines.unitCode", null,
                        List.of(new MappingRule.Transform("lookup", null, Map.of(), "MAYBE"))),
                new MappingRule("buyer.name", null, List.of())));

        assertThat(violations).extracting(RuleEngine.Violation::path).containsExactlyInAnyOrder(
                "rules[0].target", "rules[1].target", "rules[2].source.path", "rules[3].transforms[0].op",
                "rules[4].transforms[0].table", "rules[4].transforms[0].fallback", "rules[5].target");
    }
}
