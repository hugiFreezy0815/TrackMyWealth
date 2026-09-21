package com.trackmywealth.backend.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

/**
 * A plain unit test with a fixed clock: the whole point of {@link BusinessDateService} is that
 * "today" is decided by the configured business zone, not by the JVM's (UTC in a container).
 */
class BusinessDateServiceTest {

  private static Clock utcClockAt(String instant) {
    return Clock.fixed(Instant.parse(instant), ZoneOffset.UTC);
  }

  @Test
  void justAfterLocalMidnightTheBusinessDateIsAlreadyTheNewDayWhileUtcIsStillTheOldOne() {
    // 23:30 UTC on 14 March is 00:30 CET on 15 March in Zurich: a purchase booked "today" by a
    // Swiss user is dated the 15th and must not be treated as future-dated by a UTC server.
    BusinessDateService service =
        new BusinessDateService(utcClockAt("2026-03-14T23:30:00Z"), "Europe/Zurich");

    assertThat(service.today()).isEqualTo(LocalDate.of(2026, 3, 15));
  }

  @Test
  void duringSummerTimeTheOffsetIsTwoHours() {
    // 22:30 UTC on 14 July is 00:30 CEST on 15 July.
    BusinessDateService service =
        new BusinessDateService(utcClockAt("2026-07-14T22:30:00Z"), "Europe/Zurich");

    assertThat(service.today()).isEqualTo(LocalDate.of(2026, 7, 15));
  }

  @Test
  void midDayAgreesWithUtc() {
    BusinessDateService service =
        new BusinessDateService(utcClockAt("2026-03-14T12:00:00Z"), "Europe/Zurich");

    assertThat(service.today()).isEqualTo(LocalDate.of(2026, 3, 14));
  }

  @Test
  void aMisspelledZoneFailsAtConstructionNotOnTheFirstBalanceRead() {
    assertThatThrownBy(
            () -> new BusinessDateService(utcClockAt("2026-03-14T12:00:00Z"), "Europe/Zurch"))
        .isInstanceOf(DateTimeException.class);
  }
}
