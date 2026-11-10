package com.trackmywealth.backend.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.trackmywealth.backend.dto.ValueHistoryPeriod;
import java.math.BigDecimal;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** Period boundaries, density and arithmetic in US-11-06's server-side chart contract. */
class ValueHistoryPeriodTest {

  private static final LocalDate TODAY = LocalDate.of(2026, 11, 9);

  @Test
  void dailyPeriodsIncludeTodayWithoutMissingTheStart() {
    assertThat(ValueHistoryPeriod.ONE_WEEK.dates(TODAY.minusWeeks(1), TODAY))
        .hasSize(8)
        .startsWith(TODAY.minusWeeks(1))
        .endsWith(TODAY);
    assertThat(ValueHistoryPeriod.ONE_MONTH.dates(TODAY.minusMonths(1), TODAY))
        .hasSize(32)
        .endsWith(TODAY);
  }

  @Test
  void yearlyPeriodsAreWeeklyAndHaveAtMostOneFinalPartialWeek() {
    var dates = ValueHistoryPeriod.ONE_YEAR.dates(TODAY.minusYears(1), TODAY);
    assertThat(dates).hasSizeLessThanOrEqualTo(54).endsWith(TODAY);
    assertThat(dates.get(0)).isEqualTo(TODAY.minusYears(1));
    assertThat(ValueHistoryPeriod.YEAR_TO_DATE.start(TODAY, LocalDate.MIN))
        .isEqualTo(LocalDate.of(2026, 1, 1));
  }

  @Test
  void maximumPeriodNeverReturnsMoreThanFourHundredPoints() {
    var dates = ValueHistoryPeriod.MAX.dates(LocalDate.of(1800, 1, 1), TODAY);
    assertThat(dates).hasSizeLessThanOrEqualTo(400).endsWith(TODAY);
    assertThat(ValueHistoryPeriod.MAX.start(TODAY, TODAY.plusDays(1))).isEqualTo(TODAY);
  }

  @Test
  void unknownCapitalMovementsDoNotBecomeMarketPerformance() {
    var change =
        ValueHistoryService.change(
            new BigDecimal("1000"),
            new BigDecimal("1200"),
            new BigDecimal("100"),
            null);
    assertThat(change.contributions()).isEqualByComparingTo("100");
    assertThat(change.marketAndFx()).isNull();
  }

  @Test
  void invalidPeriodUsesStableValidationError() {
    assertThatThrownBy(() -> ValueHistoryPeriod.parse("2Y"))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(
            exception ->
                assertThat(((ResponseStatusException) exception).getStatusCode())
                    .isEqualTo(HttpStatus.BAD_REQUEST));
  }

  @Test
  void periodChangeUsesAbsoluteStartingValueAndRoundsOnlyPercent() {
    var change =
        ValueHistoryService.change(
            new BigDecimal("100000.00"), new BigDecimal("103000.00"), new BigDecimal("5000.00"));
    assertThat(change.amount()).isEqualByComparingTo("3000");
    assertThat(change.percent()).isEqualByComparingTo("3.00");
    assertThat(change.contributions()).isEqualByComparingTo("5000");
    assertThat(change.marketAndFx()).isEqualByComparingTo("-2000");

    var newWorkspace =
        ValueHistoryService.change(BigDecimal.ZERO, new BigDecimal("10"), BigDecimal.ZERO);
    assertThat(newWorkspace.percent()).isNull();
    assertThat(newWorkspace.amount()).isEqualByComparingTo("10");

    var negativeStart =
        ValueHistoryService.change(new BigDecimal("-100"), new BigDecimal("-90"), BigDecimal.ZERO);
    assertThat(negativeStart.percent()).isEqualByComparingTo("10.00");
  }
}
