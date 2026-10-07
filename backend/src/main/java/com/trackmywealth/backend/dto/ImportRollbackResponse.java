package com.trackmywealth.backend.dto;

import java.util.List;
import java.util.UUID;

/**
 * The outcome of rolling back an import batch (US-07-05). {@code batch} is its summary, now {@code
 * ROLLED_BACK} or {@code VOIDED}, with the version for its {@code ETag}. {@code rollback} says
 * which: {@code HARD_DELETE} or {@code VOID} ({@link ImportRollbackValues}).
 *
 * <ul>
 *   <li>{@code HARD_DELETE}: {@code deletedTransactionCount} transactions no longer exist; {@code
 *       modified}, {@code voidedTransactionIds} and {@code reversalTransactionIds} are empty.
 *   <li>{@code VOID}: {@code modified} names every transaction that made the batch count as
 *       modified, with why; {@code voidedTransactionIds} every row this rollback voided - the
 *       batch's still active rows, and a linked row voided with one of them (a purchase's FEE row)
 *       - and {@code reversalTransactionIds} the reversing rows added, in the same order. Rows
 *       voided or deleted before are left alone.
 * </ul>
 *
 * <p>Ids rather than rows: a batch holds thousands of rows, and the ledger endpoints read the ones
 * a client shows. Either way {@code unmatchedTransactionIds} names the other leg of every
 * settlement or transfer match the rollback dissolved, which counts as an ordinary payment or
 * credit again (FR-LIF-007).
 */
public record ImportRollbackResponse(
    ImportBatchResponse batch,
    String rollback,
    int deletedTransactionCount,
    List<ImportRollbackModifiedResponse> modified,
    List<UUID> voidedTransactionIds,
    List<UUID> reversalTransactionIds,
    List<UUID> unmatchedTransactionIds) {

  public ImportRollbackResponse {
    modified = List.copyOf(modified);
    voidedTransactionIds = List.copyOf(voidedTransactionIds);
    reversalTransactionIds = List.copyOf(reversalTransactionIds);
    unmatchedTransactionIds = List.copyOf(unmatchedTransactionIds);
  }
}
