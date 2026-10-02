package com.trackmywealth.backend.dto;

import java.util.List;
import java.util.UUID;

/**
 * The outcome of removing or restoring a transaction (US-07-02/07-07). Nothing is removed silently
 * (FR-LIF-007): {@code affected} lists every row the action touched - the transaction itself and a
 * card purchase's linked FEE row - as it is now; {@code reversals} lists the reversing rows a void
 * added; for a void restore it contains the append-only undo row (empty for a soft-delete restore); {@code unmatchedTransactionIds} names the other leg
 * of every settlement match the removal dissolved, which now counts as an ordinary payment or
 * credit again.
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
