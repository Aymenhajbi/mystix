package ma.mystix.tenant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class IceTest {

    @Test
    void acceptsFifteenDigits() {
        assertThat(new Ice("001234567000089").value()).isEqualTo("001234567000089");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "12345678901234", "1234567890123456", "00123456700008A", " 01234567000089"})
    void rejectsAnythingElse(String candidate) {
        assertThat(Ice.isValid(candidate)).isFalse();
        assertThatIllegalArgumentException().isThrownBy(() -> new Ice(candidate));
    }
}
