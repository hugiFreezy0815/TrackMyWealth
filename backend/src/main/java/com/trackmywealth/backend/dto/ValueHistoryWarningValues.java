package com.trackmywealth.backend.dto;

/**
 * Stable codes for the data-quality warnings of a value history (US-11-06, #496 review). {@code
 * ValueHistoryResponse.warnings} keeps the account-specific sentences for support; clients choose
 * their text from {@code warningCodes}, which also carries the account-level {@link
 * DataQualityWarningValues} codes unchanged. Plain {@code String} constants, like {@link
 * ValueBasisValues}.
 */
public final class ValueHistoryWarningValues {

  private ValueHistoryWarningValues() {}

  /**
   * An account's value is unknown on a chart date, at its inception or at a portfolio/lifecycle
   * change (for example a missing historical FX rate). Unknown values are left out of a point's
   * sum, so the period change can be distorted, not only its split.
   */
  public static final String VALUE_UNKNOWN = "VALUE_UNKNOWN";

  /** An account without an opening date may have existed before its first recorded activity. */
  public static final String INCEPTION_UNCERTAIN = "INCEPTION_UNCERTAIN";

  /** Lifecycle or portfolio history before tracking began is reconstructed, not recorded. */
  public static final String LIFECYCLE_UNVERIFIED = "LIFECYCLE_UNVERIFIED";

  /** Transactions pending review keep the contributions split from being complete. */
  public static final String ATTRIBUTION_PENDING_REVIEW = "ATTRIBUTION_PENDING_REVIEW";

  /** A historical FX rate needed for contributions or capital flows is unavailable. */
  public static final String ATTRIBUTION_FX_UNAVAILABLE = "ATTRIBUTION_FX_UNAVAILABLE";

  /** A portfolio's capital movements could not be attributed. */
  public static final String CAPITAL_MOVEMENTS_UNAVAILABLE = "CAPITAL_MOVEMENTS_UNAVAILABLE";
}
