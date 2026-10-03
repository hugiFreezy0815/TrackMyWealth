package com.trackmywealth.backend.dto;

/**
 * Data-quality warnings a figure carries so a client can show it is not fully trustworthy, at the
 * account and at the consolidated headline (PR-011, FR-CON-007). Plain {@code String} constants,
 * like {@link ValueBasisValues}.
 */
public final class DataQualityWarningValues {

  private DataQualityWarningValues() {}

  /**
   * The account has live transactions booked before its opening balance (US-25-04). They predate
   * the starting point, so its value leaves them out: either the opening date is too late or those
   * rows were entered on top of a balance that already contains them. The member confirmed this
   * when recording the opening balance, or added such rows afterwards.
   */
  public static final String TRANSACTIONS_BEFORE_OPENING_BALANCE =
      "TRANSACTIONS_BEFORE_OPENING_BALANCE";
}
