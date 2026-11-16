package com.trackmywealth.backend.config;

import java.time.Clock;
import java.time.ZoneOffset;
import org.springframework.boot.validation.autoconfigure.ValidationConfigurationCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The "now" behind Bean Validation's temporal constraints ({@code @PastOrPresent}, ...) on request
 * dates. A member enters a calendar date in their phone's own calendar, and the server cannot know
 * that phone's zone: a client-sent zone would not help either, since any client may claim the
 * earliest one. So a date is judged by the earliest calendar on Earth (UTC+14): it is in the future
 * only once it is not "today" anywhere (#585). The phone's today is always accepted; a date more
 * than about one day ahead of every zone is still rejected. Hibernate Validator's default would be
 * the JVM zone (UTC in the container), which rejects a Swiss member's "today" after midnight.
 *
 * <p>The date itself is stored as entered: a calendar date has no zone to convert from.
 */
@Configuration
public class ValidationClockConfig {

  /** The earliest calendar date in use anywhere (Line Islands, Kiribati). */
  static final ZoneOffset EARLIEST_ZONE = ZoneOffset.ofHours(14);

  @Bean
  ValidationConfigurationCustomizer earliestZoneClockProvider(Clock clock) {
    return configuration -> configuration.clockProvider(() -> clock.withZone(EARLIEST_ZONE));
  }
}
