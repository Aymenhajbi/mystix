package ma.mystix.clearance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.UUID;

import ma.mystix.shared.Sha256;
import org.junit.jupiter.api.Test;

class SimulatedClearanceGatewayTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-02T10:00:00Z"), ZoneId.of("Africa/Casablanca"));

    @Test
    void clearsWithADeterministicSimulatedReference() {
        SimulatedClearanceGateway gateway = new SimulatedClearanceGateway(CLOCK, "");
        ClearanceRequest request = request("FA-1", "<Invoice/>");

        ClearanceResult first = gateway.submit(request);
        ClearanceResult again = gateway.submit(request);

        assertThat(first.outcome()).isEqualTo(ClearanceResult.Outcome.CLEARED);
        assertThat(first.simulated()).isTrue();
        assertThat(first.reference())
                .startsWith("SIMULATED-")
                .hasSize("SIMULATED-".length() + 20)
                .isEqualTo(again.reference());
        assertThat(first.at().toInstant()).isEqualTo(CLOCK.instant());
        assertThat(gateway.simulated()).isTrue();
    }

    @Test
    void rejectsNumbersMatchingTheConfiguredPattern() {
        SimulatedClearanceGateway gateway = new SimulatedClearanceGateway(CLOCK, "FA-SIMREJECT-.*");

        ClearanceResult rejected = gateway.submit(request("FA-SIMREJECT-7", "<Invoice/>"));
        ClearanceResult cleared = gateway.submit(request("FA-8", "<Invoice/>"));

        assertThat(rejected.outcome()).isEqualTo(ClearanceResult.Outcome.REJECTED);
        assertThat(rejected.reference()).isNull();
        assertThat(rejected.reason()).contains("Simulated");
        assertThat(cleared.outcome()).isEqualTo(ClearanceResult.Outcome.CLEARED);
    }

    @Test
    void onlyTheSimulatedModeCanStart() {
        ClearanceConfig config = new ClearanceConfig();

        assertThat(config.clearanceGateway(CLOCK, "simulated", "").simulated()).isTrue();
        assertThatIllegalStateException().isThrownBy(() -> config.clearanceGateway(CLOCK, "dgi", ""))
                .withMessageContaining("only 'simulated' exists");
    }

    private static ClearanceRequest request(String number, String document) {
        byte[] bytes = document.getBytes(StandardCharsets.UTF_8);
        return new ClearanceRequest(UUID.randomUUID(), UUID.randomUUID(), number, bytes, Sha256.hex(bytes));
    }
}
