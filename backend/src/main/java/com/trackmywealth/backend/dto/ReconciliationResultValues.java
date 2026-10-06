package com.trackmywealth.backend.dto;

/**
 * Stable values of one {@code reconciliation_result} row (US-25-02, FR-REC-003, FR-STA-003): its
 * status and its best-effort probable cause. Both mirror V11's CHECK constraints and are part of
 * the {@link ReconciliationResultResponse} contract.
 */
public final class ReconciliationResultValues {

  /** The snapshot and the derived ledger disagree. */
  public static final String OPEN = "OPEN";

  /** A later source change made the snapshot and the derived ledger agree again. */
  public static final String RESOLVED = "RESOLVED";

  /**
   * A member accepted the provider's figure (US-25-03): a visible {@code VALUATION_ADJUSTMENT}
   * entry closes the gap. The engine reopens it only when the account stops agreeing.
   */
  public static final String ACCEPTED = "ACCEPTED";

  /**
   * A member dismissed the difference with a reason (US-25-03). The decision covers that amount
   * only: the engine resolves it on agreement and reopens it when the difference changes.
   */
  public static final String DISMISSED = "DISMISSED";

  /** A newer snapshot, or a lost comparison basis, replaced this comparison. */
  public static final String SUPERSEDED = "SUPERSEDED";

  public static final String CAUSE_DUPLICATE_ENTRY = "DUPLICATE_ENTRY";

  /**
   * FR-REC-003: an import of the account holds the missing booking but did not import it (#230) - a
   * discarded batch, an error row, or a row left out of a committed batch.
   */
  public static final String CAUSE_MISSING_TRANSACTION = "MISSING_TRANSACTION";

  public static final String CAUSE_FX_ROUNDING = "FX_ROUNDING";
  public static final String CAUSE_UNRECORDED_FEE = "UNRECORDED_FEE";
  public static final String CAUSE_UNKNOWN = "UNKNOWN";

  private ReconciliationResultValues() {}
}
