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

  /** A member accepted the difference (US-25-03); never set or overwritten by the engine. */
  public static final String ACCEPTED = "ACCEPTED";

  /** A member dismissed the difference (US-25-03); never set or overwritten by the engine. */
  public static final String DISMISSED = "DISMISSED";

  /** A newer snapshot, or a lost comparison basis, replaced this comparison. */
  public static final String SUPERSEDED = "SUPERSEDED";

  public static final String CAUSE_DUPLICATE_ENTRY = "DUPLICATE_ENTRY";
  public static final String CAUSE_FX_ROUNDING = "FX_ROUNDING";
  public static final String CAUSE_UNRECORDED_FEE = "UNRECORDED_FEE";
  public static final String CAUSE_UNKNOWN = "UNKNOWN";

  private ReconciliationResultValues() {}
}
