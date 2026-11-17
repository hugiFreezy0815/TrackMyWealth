package com.trackmywealth.backend.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.trackmywealth.backend.dto.UpdateAccountRequest;
import com.trackmywealth.backend.testsupport.MutableClock;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import org.hibernate.validator.HibernateValidator;
import org.hibernate.validator.HibernateValidatorConfiguration;
import org.hibernate.validator.messageinterpolation.ParameterMessageInterpolator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * #585: an account's opening date is the member's phone date. {@code @PastOrPresent} accepts the
 * phone's "today" in any zone and rejects only a date that is not today anywhere yet.
 */
class ValidationClockConfigTest {

  private final MutableClock clock = new MutableClock();
  private ValidatorFactory factory;
  private Validator validator;

  @BeforeEach
  void buildValidator() {
    HibernateValidatorConfiguration configuration =
        Validation.byProvider(HibernateValidator.class)
            .configure()
            .messageInterpolator(new ParameterMessageInterpolator());
    new ValidationClockConfig().earliestZoneClockProvider(clock).customize(configuration);
    factory = configuration.buildValidatorFactory();
    validator = factory.getValidator();
  }

  @AfterEach
  void close() {
    factory.close();
  }

  @Test
  void acceptsZurichsTodayWhileUtcIsStillYesterday() {
    clock.set(Instant.parse("2026-01-01T23:30:00Z"));
    assertThat(phoneToday("Europe/Zurich")).isEqualTo(LocalDate.of(2026, 1, 2));

    assertThat(validator.validate(opened(phoneToday("Europe/Zurich")))).isEmpty();
  }

  @Test
  void acceptsAPhonesTodayThatIsAlreadyTomorrowInZurich() {
    clock.set(Instant.parse("2026-01-01T11:30:00Z"));
    assertThat(phoneToday("Pacific/Auckland")).isEqualTo(LocalDate.of(2026, 1, 2));
    assertThat(phoneToday("Europe/Zurich")).isEqualTo(LocalDate.of(2026, 1, 1));

    assertThat(validator.validate(opened(phoneToday("Pacific/Auckland")))).isEmpty();
  }

  @Test
  void rejectsADateThatIsNotTodayAnywhereYet() {
    clock.set(Instant.parse("2026-01-01T11:30:00Z"));
    // UTC+14 is at 01:30 on 2 Jan: 3 Jan has not begun in any zone.
    assertThat(validator.validate(opened(LocalDate.of(2026, 1, 3))))
        .extracting(violation -> violation.getPropertyPath().toString())
        .containsExactly("openedAt");
  }

  private LocalDate phoneToday(String zone) {
    return LocalDate.now(clock.withZone(ZoneId.of(zone)));
  }

  private static UpdateAccountRequest opened(LocalDate openedAt) {
    return new UpdateAccountRequest("Checking", "CASH", "CHF", null, openedAt, null);
  }
}
