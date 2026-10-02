package com.trackmywealth.backend.dto;

import java.util.List;
import java.util.UUID;

/**
 * The outcome of removing or restoring a transaction (US-07-02/07-07). Nothing is removed silently
 * (FR-LIF-007): {@code affected} lists every lifecycle row touched. {@code reversals} lists rows
 * appended to reverse a void, including the undo row appended by a void restore. It is empty for
 * soft-delete operations. {@code unmatchedTransactionIds} names other settlement legs freed by a
 * removal.
 */
public record TransactionRemovalResponse(
    String removal,
    int version,
    List<TransactionResponse> affected,
    List<TransactionResponse> reversals,
    List<UUID> unmatchedTransactionIds) {

  public TransactionRemovalResponse {
    affected = List.copyOf(affected);
    reversals = List.copyOf(reversals);
    unmatchedTransactionIds = List.copyOf(unmatchedTransactionIds);
  }
}
