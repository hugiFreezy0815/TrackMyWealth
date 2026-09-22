package com.trackmywealth.backend.testsupport;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * Substitutes {@link MutableClock} for the application's real {@code Clock} bean. A nested
 * {@code @TestConfiguration} is auto-detected by {@code @SpringBootTest} only inside its own test
 * class, so a test that wants this shared one imports it explicitly with {@code @Import}.
 */
@TestConfiguration
public class TestClockConfig {

  @Bean
  @Primary
  MutableClock testClock() {
    return new MutableClock();
  }
}
