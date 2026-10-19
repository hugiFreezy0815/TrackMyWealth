package com.trackmywealth.backend.dto;

import java.util.List;
import java.util.UUID;

/**
 * US-07-22 (#325): the transfers an import's own file pairs, decided before its rows are recorded
 * and applied once they are, before transfer matching runs ({@code ImportTransferLinkService}).
 *
 * @param exchanges the legs of each currency exchange, by position in the rows recorded
 * @param pocketMoves the savings pocket moves, by position in the rows recorded
 * @param savingsPocketAccountId where the pocket moves' other legs are booked; {@code null} when
 *     the account's pocket cannot take them, which leaves them one-sided transfers
 */
public record ImportTransferPlan(
    List<ImportRowPair> exchanges, List<Integer> pocketMoves, UUID savingsPocketAccountId) {

  public ImportTransferPlan {
    exchanges = List.copyOf(exchanges);
    pocketMoves = List.copyOf(pocketMoves);
  }

  /** Whether recording the rows involves nothing to link. */
  public boolean isEmpty() {
    return exchanges.isEmpty() && (pocketMoves.isEmpty() || savingsPocketAccountId == null);
  }
}
