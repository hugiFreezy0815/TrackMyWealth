package com.trackmywealth.backend.dto;

import java.util.List;

/**
 * How an import batch was rolled back (US-07-05, FR-LIF-010/011) and why a batch counted as
 * modified. The system decides the rollback, never the member: a batch none of whose transactions
 * was worked on is deleted, any other is voided. Each criterion code names one way a transaction
 * was worked on; they are contract and are never renamed.
 */
public final class ImportRollbackValues {

  /** FR-LIF-010: nobody worked on the batch; its transactions were deleted. */
  public static final String HARD_DELETE = "HARD_DELETE";

  /** FR-LIF-011: someone worked on the batch; its transactions were voided (T2). */
  public static final String VOID = TransactionRemovalValues.VOID;

  /** The transaction was corrected (US-07-06): a row with {@code corrects_transaction_id}. */
  public static final String CORRECTED = "CORRECTED";

  /** The transaction was voided or deleted on its own (US-07-02), not by a correction. */
  public static final String REMOVED = "REMOVED";

  /** A member chose the transaction's category (US-08-02), whether or not it still stands. */
  public static final String USER_CATEGORY_OVERRIDE = "USER_CATEGORY_OVERRIDE";

  /** The transaction's amount was split across categories. */
  public static final String CATEGORY_SPLIT = "CATEGORY_SPLIT";

  /**
   * The transaction is a leg of a settlement or transfer match a member confirmed or rejected. A
   * match the system decided by itself does not count.
   */
  public static final String MATCH_DECIDED = "MATCH_DECIDED";

  /** A reconciliation result names the transaction as its resolution (US-25-03). */
  public static final String RECONCILIATION_RESOLUTION = "RECONCILIATION_RESOLUTION";

  /** The transaction's void was restored (US-07-07): a row with {@code restores_transaction_id}. */
  public static final String RESTORED = "RESTORED";

  /**
   * Something outside the batch points at the transaction: a row of another batch or account
   * ({@code related_transaction_id}), another batch's preview row found to duplicate it, or a tax
   * lot.
   */
  public static final String REFERENCED = "REFERENCED";

  /** Every criterion, in the order a response lists a transaction's reasons. */
  public static final List<String> CRITERIA =
      List.of(
          CORRECTED,
          REMOVED,
          USER_CATEGORY_OVERRIDE,
          CATEGORY_SPLIT,
          MATCH_DECIDED,
          RECONCILIATION_RESOLUTION,
          RESTORED,
          REFERENCED);

  private ImportRollbackValues() {}
}
