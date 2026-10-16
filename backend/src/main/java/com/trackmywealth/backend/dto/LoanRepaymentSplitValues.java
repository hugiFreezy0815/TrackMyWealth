package com.trackmywealth.backend.dto;

/**
 * Where a loan payment's interest/principal split came from (US-10-02, {@code
 * loan_payment.split_source}). Plain {@code String} constants, matching every other value set in
 * this codebase (see {@link AccessLevelValues}).
 */
public final class LoanRepaymentSplitValues {

  private LoanRepaymentSplitValues() {}

  /** The loan's amortisation schedule entry for the payment's period. */
  public static final String SCHEDULE = "SCHEDULE";

  /** Given by the caller, e.g. a statement that books interest and instalment separately. */
  public static final String STATED = "STATED";

  /** Outstanding balance x interest rate / 12 - an estimate (PR-011). */
  public static final String ESTIMATED = "ESTIMATED";

  /** Nothing to split by: the payment is pending review until it is entered again (PR-011). */
  public static final String UNKNOWN = "UNKNOWN";
}
