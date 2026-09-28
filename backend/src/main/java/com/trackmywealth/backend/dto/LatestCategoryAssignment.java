package com.trackmywealth.backend.dto;

import java.util.UUID;

/**
 * How one transaction got its current category: the {@code assigned_by} of its latest {@code
 * transaction_categorization_log} row (US-08-01). A query projection of {@code
 * TransactionCategorizationLogRepository#findLatestAssignments}; internal, not part of any API
 * response. It lives here because {@code ArchitectureTest} allows only repositories in {@code
 * ..repository..}.
 */
public interface LatestCategoryAssignment {
  UUID getTransactionId();

  String getAssignedBy();
}
