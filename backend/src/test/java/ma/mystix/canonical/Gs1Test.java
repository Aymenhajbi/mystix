package ma.mystix.canonical;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class Gs1Test {

    @ParameterizedTest
    @ValueSource(strings = {"4006381333931", "6110000000109", "96385074", "036000291452", "00611000000015"})
    void acceptsValidGtins(String gtin) {
        assertThat(Gs1.isValidGtin(gtin)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"4006381333932", "611000000010", "6110000000109A", "", "123456789012345"})
    void rejectsInvalidGtins(String gtin) {
        assertThat(Gs1.isValidGtin(gtin)).isFalse();
    }

    @Test
    void checksGlnAndSscc() {
        assertThat(Gs1.isValidGln("6110000000017")).isTrue();
        assertThat(Gs1.isValidGln("6110000000018")).isFalse();
        assertThat(Gs1.isValidSscc("106141411234567897")).isTrue();
        assertThat(Gs1.isValidSscc("106141411234567898")).isFalse();
    }
}
