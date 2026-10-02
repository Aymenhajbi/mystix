package ma.mystix.clearance;

import java.time.Clock;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
class ClearanceConfig {

    /**
     * Only {@code simulated} exists. Any other mode fails at startup instead of silently simulating.
     * TODO(DGI-SPEC): add the real DGI adapter (Lot 9) once the specifications are published.
     */
    @Bean
    ClearanceGateway clearanceGateway(Clock clock,
                                      @Value("${mystix.clearance.mode:simulated}") String mode,
                                      @Value("${mystix.clearance.simulated.reject-numbers-matching:}") String reject) {
        if (!"simulated".equals(mode)) {
            throw new IllegalStateException("Unsupported clearance mode '" + mode
                    + "': only 'simulated' exists until the DGI specifications are published");
        }
        return new SimulatedClearanceGateway(clock, reject);
    }
}
