package com.trackmywealth.backend.dto;

import java.util.List;
import java.util.UUID;

/**
 * The outcome of editing a transaction (US-07-06, FR-LIF-004). {@code transaction} is the row as it
 * now stands - the same row after a non-financial edit, otherwise the new replacement, whose {@code
 * correctsTransactionId} names the original - and {@code version} (the response's ETag) is its
 * version.
 *
 * <p>{@code removal} is {@code null} for an in-place edit. For a correction it says how the
 * original was removed ({@link TransactionRemovalValues}), and, as for a removal (FR-LIF-007),
 * {@code affected} lists the original as it is now, {@code reversals} the reversing row a void
 * added, and {@code unmatchedTransactionIds} the other leg of every settlement match the removal
 * dissolved. All three are empty for an in-place edit.
 */
public record TransactionCorrectionResponse(
    String removal,
    int version,
    TransactionResponse transaction,
    List<TransactionResponse> affected,
    List<TransactionResponse> reversals,
    List<UUID> unmatchedTransactionIds) {

  public TransactionCorrectionResponse {
    affected = List.copyOf(affected);
    reversals = List.copyOf(reversals);
    unmatchedTransactionIds = List.copyOf(unmatchedTransactionIds);
  }
}
