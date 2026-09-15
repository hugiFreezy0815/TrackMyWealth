package com.trackmywealth.backend.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Result of {@code FxRateService.getRate}. Lives in {@code dto}, not {@code service}, purely for
 * ArchUnit's {@code services_are_named_consistently} rule (every class in {@code ..service..} must
 * be {@code @Service}-annotated and end with "Service") - nothing crosses the REST boundary with
 * this shape yet (US-06-01 is storage/read-contract only, no controller consumes it this sprint).
 *
 * @param rate multiply an amount in the queried base currency by this to get the quote currency
 *     amount
 * @param requestedDate the date that was asked for
 * @param rateDate the date the returned rate actually applies to - equal to {@code requestedDate}
 *     for an exact match, earlier when carried forward
 */
public record FxRateLookupResult(
    BigDecimal rate, LocalDate requestedDate, LocalDate rateDate, String source) {

  /**
   * True when no rate was stored for {@code requestedDate} itself and the last available prior rate
   * was used instead (FR-CUR-012/PR-011) - callers must visibly mark any figure derived from this
   * the same way, never display it with the same authority as an exact rate. Derived rather than
   * stored: {@code rateDate} and {@code requestedDate} are the only facts a caller can actually
   * supply, and keeping this as a second, independently-settable field would let the two disagree.
   */
  public boolean carriedForward() {
    return !rateDate.equals(requestedDate);
  }
}
