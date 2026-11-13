package com.trackmywealth.backend.dto;

import java.util.Comparator;

/**
 * One value-history warning: a stable {@code code} ({@link ValueHistoryWarningValues} or {@link
 * DataQualityWarningValues}) and the account-specific sentence behind it.
 */
public record HistoryWarning(String code, String message) implements Comparable<HistoryWarning> {

  private static final Comparator<HistoryWarning> ORDER =
      Comparator.comparing(HistoryWarning::message).thenComparing(HistoryWarning::code);

  /** An account-level data-quality code travels with the history unchanged. */
  public static HistoryWarning dataQuality(String code) {
    return new HistoryWarning(code, code);
  }

  @Override
  public int compareTo(HistoryWarning other) {
    return ORDER.compare(this, other);
  }
}
