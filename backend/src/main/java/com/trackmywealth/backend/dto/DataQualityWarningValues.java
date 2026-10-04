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
   *
   * <p>Shown at {@code BALANCE_ONLY} too: it qualifies the figure that grant sees (PR-011), so it
   * travels with it. It says only that such rows exist - never how many, when or how much, which
   * stay behind {@code READ} (#241 review).
   *
   * <p>A finalized reconciliation adjustment (US-25-03) left before a later-moved opening balance
   * does not raise it: the new starting point contains that correction, and the row is locked to
   * its result, so there is nothing to act on. The row still carries {@link
   * #BOOKED_BEFORE_OPENING_BALANCE}.
   */
  public static final String TRANSACTIONS_BEFORE_OPENING_BALANCE =
      "TRANSACTIONS_BEFORE_OPENING_BALANCE";

  /**
   * On a single transaction: it is booked before its account's opening balance, so the account's
   * value leaves it out (US-25-04). Shown on the row itself - in particular in the response to
   * recording, correcting or restoring it - so the member learns at once that it does not count,
   * not only from the account-level {@link #TRANSACTIONS_BEFORE_OPENING_BALANCE} (#241 review).
   */
  public static final String BOOKED_BEFORE_OPENING_BALANCE = "BOOKED_BEFORE_OPENING_BALANCE";

  /** An account's newest observed balance disagrees with its derived ledger value (US-25-02). */
  public static final String OPEN_RECONCILIATION_DIFFERENCE = "OPEN_RECONCILIATION_DIFFERENCE";
}
