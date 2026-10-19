package ma.mystix.shared;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class GtinTest {

    @Test
    void validCodesOfEveryLengthAreNormalisedTo14Digits() {
        // EAN-13 printed on a Moroccan mineral water bottle (prefix 611, GS1 Morocco).
        assertThat(Gtin.parse("6111035000430")).hasValueSatisfying(g -> {
            assertThat(g.gtin14()).isEqualTo("06111035000430");
            assertThat(g.length()).isEqualTo(13);
            assertThat(g.prefix()).isEqualTo(Gtin.Prefix.GS1_MOROCCO);
        });
        assertThat(Gtin.parse("96385074")).hasValueSatisfying(g -> assertThat(g.gtin14()).isEqualTo("00000096385074"));
        assertThat(Gtin.parse(" 0 36000 29145 2 ")).hasValueSatisfying(g -> assertThat(g.length()).isEqualTo(12));
        assertThat(Gtin.parse("10614141000415")).hasValueSatisfying(g -> assertThat(g.length()).isEqualTo(14));
    }

    @Test
    void aWrongCheckDigitOrLengthIsRefused() {
        assertThat(Gtin.parse("6111035000431")).isEmpty();
        assertThat(Gtin.parse("12345")).isEmpty();
        assertThat(Gtin.parse("61110350004301")).isEmpty();
        assertThat(Gtin.parse("abc")).isEmpty();
        assertThat(Gtin.parse(null)).isEmpty();
        assertThat(Gtin.checkDigitValid("6111035000430")).isTrue();
    }

    @Test
    void restrictedCirculationNumbersAreRecognised() {
        String rcn = "200000000001" + Gtin.checkDigit("200000000001");
        assertThat(Gtin.parse(rcn)).hasValueSatisfying(g -> assertThat(g.prefix()).isEqualTo(Gtin.Prefix.RESTRICTED_CIRCULATION));
    }
}
