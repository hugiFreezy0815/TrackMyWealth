package com.trackmywealth.backend.dto;

import java.util.Comparator;

/**
 * One value-history warning: a stable {@code code} ({@link ValueHistoryWarningValues} or {@link
 * DataQualityWarningValues}) and the account-specific sentence behind it.
 */
public record HistoryWarning(String code, String message) {

  /** Sorted by sentence, then code: the order {@code ValueHistoryResponse.warnings} uses. */
  public static final Comparator<HistoryWarning> ORDER =
      Comparator.comparing(HistoryWarning::message).thenComparing(HistoryWarning::code);

  /** An account-level data-quality code travels with the history unchanged. */
  public static HistoryWarning dataQuality(String code) {
    return new HistoryWarning(code, code);
  }
}
