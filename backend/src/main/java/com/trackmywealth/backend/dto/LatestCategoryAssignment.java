package com.trackmywealth.backend.dto;

import java.util.UUID;

/**
 * The latest {@code transaction_categorization_log} row of one transaction (US-08-01/02). It says
 * how the transaction got its current category only while {@link #getCategoryId()} still equals the
 * transaction's {@code category_id}: an older reset that landed in UNCATEGORIZED, or in no category
 * for a type the engine does not categorize, wrote no log row of its own, so an older row can
 * outlive the category it described - see {@link #describes}. Since US-07-38 such a reset writes a
 * {@code RESET} row ({@code TransactionCategorizationLog#ASSIGNED_BY_RESET}), which callers skip
 * too: it says who reset, not how the category was assigned. A query projection of {@code
 * TransactionCategorizationLogRepository#findLatestAssignments}; internal, not part of any API
 * response. It lives here because {@code ArchitectureTest} allows only repositories in {@code
 * ..repository..}.
 */
public interface LatestCategoryAssignment {
  UUID getTransactionId();

  String getAssignedBy();

  UUID getCategoryId();

  UUID getRuleId();

  boolean isUserOverride();

  /** Whether this row still describes the transaction's current category. */
  default boolean describes(UUID currentCategoryId) {
    return currentCategoryId != null && currentCategoryId.equals(getCategoryId());
  }
}
