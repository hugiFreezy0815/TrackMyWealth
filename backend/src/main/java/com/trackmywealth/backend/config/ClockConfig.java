package com.trackmywealth.backend.config;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The application's time source for logic that must be testable against a controlled clock:
 * MfaService's TOTP time steps and the FX-import deadline that closes the autumn DST fall-back gap.
 * Both need deterministic boundary tests rather than sleeps or dependence on the host clock.
 */
@Configuration
public class ClockConfig {

  @Bean
  Clock clock() {
    return Clock.systemUTC();
  }
}
