package com.trackmywealth.backend.dto;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** #693: {@code amount} is the instalment, also on a replayed body stored without it. */
class AmortisationScheduleEntryResponseTest {

  @Test
  void aBodyStoredWithoutTheAmountGetsTheInstalment() {
    assertThat(entry(null).amount()).isEqualByComparingTo("1925.50");
  }

  @Test
  void aGivenAmountIsKept() {
    assertThat(entry(new BigDecimal("1925.50")).amount()).isEqualByComparingTo("1925.50");
  }

  private static AmortisationScheduleEntryResponse entry(BigDecimal amount) {
    return new AmortisationScheduleEntryResponse(
        UUID.randomUUID(),
        UUID.randomUUID(),
        LocalDate.of(2026, 11, 30),
        new BigDecimal("125.50"),
        new BigDecimal("1800.00"),
        amount,
        new BigDecimal("50000.00"),
        "CHF",
        0,
        OffsetDateTime.parse("2026-11-01T00:00:00Z"));
  }
}
