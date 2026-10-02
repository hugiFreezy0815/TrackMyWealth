package com.trackmywealth.backend.dto;

import java.util.List;
import java.util.UUID;

/**
 * The outcome of removing or restoring a transaction (US-07-02/07-07). Nothing is removed silently
 * (FR-LIF-007): {@code affected} lists every row the action touched - the transaction itself and a
 * card purchase's linked FEE row or a transfer's other leg - as it is now; {@code reversals} lists
 * the reversing rows a void added; {@code unmatchedTransactionIds} names the other leg of every
 * settlement match a removal dissolved, which now counts as an ordinary payment or credit again.
 *
 * <p>Restoring a void (US-07-07) leaves the voided rows and their reversals as they are: {@code
 * affected} lists them unchanged, and {@code restored} the new, effective copies that re-instate
 * them (each names its original in {@code restoresTransactionId}). {@code restored} is empty for
 * every other action - a soft-deleted row is restored in place and appears in {@code affected}.
 */
public record TransactionRemovalResponse(
    String removal,
    int version,
    List<TransactionResponse> affected,
    List<TransactionResponse> reversals,
    List<UUID> unmatchedTransactionIds,
    List<TransactionResponse> restored) {

  public TransactionRemovalResponse {
    affected = List.copyOf(affected);
    reversals = List.copyOf(reversals);
    unmatchedTransactionIds = List.copyOf(unmatchedTransactionIds);
    restored = List.copyOf(restored);
  }
}
