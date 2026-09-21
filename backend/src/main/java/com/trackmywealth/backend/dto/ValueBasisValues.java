package com.trackmywealth.backend.dto;

/**
 * Where an {@link AccountValuation#value()} came from, so a consumer can tell a figure that is
 * exactly what the source says from one that is an approximation or an assumption. Plain {@code
 * String} constants, matching every other type/status value set in this codebase (see {@link
 * AccessLevelValues}).
 */
public final class ValueBasisValues {

  private ValueBasisValues() {}

  /** Summed from the account's own transaction ledger (a credit card's outstanding balance). */
  public static final String LEDGER = "LEDGER";

  /**
   * The account has a ledger but no rows on it yet, so its value is an assumed zero rather than a
   * measured one. A card that already carried debt when tracking began reads 0 until that debt is
   * recorded (no opening-balance mechanism yet, EPIC 25) - an approximation.
   */
  public static final String LEDGER_EMPTY = "LEDGER_EMPTY";

  /** The latest manually recorded valuation on or before the as-of date ({@code CUSTOM_ASSET}). */
  public static final String MANUAL_VALUATION = "MANUAL_VALUATION";

  /**
   * The loan's or mortgage's original principal, <b>not</b> its current outstanding balance - no
   * amortisation tracking exists yet (EPIC 10), so this overstates what is still owed once
   * repayments have been made - an approximation.
   */
  public static final String ORIGINAL_PRINCIPAL = "ORIGINAL_PRINCIPAL";

  /** {@code true} for a basis that is an approximation or assumption, not a measured figure. */
  public static boolean isApproximate(String valueBasis) {
    return LEDGER_EMPTY.equals(valueBasis) || ORIGINAL_PRINCIPAL.equals(valueBasis);
  }
}
