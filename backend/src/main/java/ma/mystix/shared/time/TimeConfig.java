package ma.mystix.shared.time;

import java.time.Clock;
import java.time.ZoneId;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class TimeConfig {

    /** Business time zone of Mystix. Timestamps are stored as TIMESTAMPTZ. */
    public static final ZoneId BUSINESS_ZONE = ZoneId.of("Africa/Casablanca");

    @Bean
    Clock clock() {
        return Clock.system(BUSINESS_ZONE);
    }
}
