package com.trackmywealth.backend.dto;

import com.trackmywealth.backend.error.ApiException;
import com.trackmywealth.backend.error.ProblemCode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

/** Supported bounded history windows and point density (US-11-06). */
public enum ValueHistoryPeriod {
  ONE_WEEK("1W"),
  ONE_MONTH("1M"),
  YEAR_TO_DATE("YTD"),
  ONE_YEAR("1Y"),
  MAX("MAX");

  private static final int MAX_POINTS = 400;

  private final String periodCode;

  ValueHistoryPeriod(String wireValue) {
    this.periodCode = wireValue;
  }

  public String wireValue() {
    return periodCode;
  }

  public static ValueHistoryPeriod parse(String value) {
    for (ValueHistoryPeriod period : values()) {
      if (period.periodCode.equals(value)) {
        return period;
      }
    }
    throw new ApiException(
        ProblemCode.VALIDATION_FAILED, "period must be one of 1W, 1M, YTD, 1Y, MAX.");
  }

  public LocalDate start(LocalDate today, LocalDate firstActivity) {
    return switch (this) {
      case ONE_WEEK -> today.minusWeeks(1);
      case ONE_MONTH -> today.minusMonths(1);
      case YEAR_TO_DATE -> today.withDayOfYear(1);
      case ONE_YEAR -> today.minusYears(1);
      case MAX -> firstActivity.isAfter(today) ? today : firstActivity;
    };
  }

  /** Inclusive endpoints, ending exactly at the current business date. */
  public List<LocalDate> dates(LocalDate from, LocalDate to) {
    if (from.isAfter(to)) {
      throw new IllegalArgumentException("from must be on or before to");
    }
    long step =
        switch (this) {
          case ONE_WEEK, ONE_MONTH -> 1;
          case YEAR_TO_DATE, ONE_YEAR -> 7;
          case MAX ->
              Math.max(
                  1,
                  (long)
                      Math.ceil(
                          (double)
                                  ChronoUnit.MONTHS.between(
                                      YearMonth.from(from), YearMonth.from(to))
                              / (MAX_POINTS - 1)));
        };
    List<LocalDate> dates = new ArrayList<>();
    LocalDate current = from;
    while (current.isBefore(to)) {
      dates.add(current);
      current =
          switch (this) {
            case ONE_WEEK, ONE_MONTH -> current.plusDays(step);
            case YEAR_TO_DATE, ONE_YEAR -> current.plusWeeks(1);
            case MAX -> current.plusMonths(step);
          };
    }
    dates.add(to);
    return List.copyOf(dates);
  }
}
