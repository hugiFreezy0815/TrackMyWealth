package com.trackmywealth.backend.config;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The application's time source for logic that must be testable against a controlled clock - today
 * MfaService's TOTP time steps, where replay protection can only be exercised deterministically by
 * advancing time in a test rather than sleeping through real 30-second steps.
 */
@Configuration
public class ClockConfig {

  @Bean
  Clock clock() {
    return Clock.systemUTC();
  }
}
