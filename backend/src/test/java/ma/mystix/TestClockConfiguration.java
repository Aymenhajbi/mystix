package ma.mystix;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;

import ma.mystix.shared.time.TimeConfig;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * A clock that starts on the issue date of the reference fixture (2026-09-15, Africa/Casablanca) and then runs
 * normally: fixture invoices are not backdated whatever the day the build runs, and the order of events is kept.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestClockConfiguration {

    public static final ZonedDateTime START = ZonedDateTime.of(2026, 9, 15, 9, 0, 0, 0, TimeConfig.BUSINESS_ZONE);

    @Bean
    @Primary
    Clock testClock() {
        return Clock.offset(Clock.system(TimeConfig.BUSINESS_ZONE), Duration.between(Instant.now(), START.toInstant()));
    }
}
