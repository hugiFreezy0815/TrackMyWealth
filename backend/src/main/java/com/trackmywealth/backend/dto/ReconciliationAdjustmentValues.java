package com.trackmywealth.backend.dto;

/**
 * US-25-03: where a {@code VALUATION_ADJUSTMENT} row stands in its reconciliation result's
 * lifecycle ({@link TransactionResponse#reconciliationAdjustment()}). Never corrected, removed,
 * restored or categorized directly in any of them.
 */
public final class ReconciliationAdjustmentValues {

  /** Its result is the newest comparison's: reopening that result withdraws the row. */
  public static final String REOPENABLE = "REOPENABLE";

  /**
   * A newer snapshot was compared against a ledger containing this row, which closed its result's
   * comparison: the row is history and stays. A correction goes into the newest comparison.
   */
  public static final String FINALIZED = "FINALIZED";

  /** Withdrawn with its result (reopened or overtaken); it no longer counts anywhere. */
  public static final String WITHDRAWN = "WITHDRAWN";

  private ReconciliationAdjustmentValues() {}
}
