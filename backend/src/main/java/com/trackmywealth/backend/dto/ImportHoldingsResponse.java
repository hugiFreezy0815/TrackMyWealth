package com.trackmywealth.backend.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * US-07-13 (#273): a holdings statement's batch - what its file holds and what its commit makes of
 * it. Positions are resolved afresh on every read: a security created (or a ticker mapped) since
 * the upload counts at once.
 *
 * <p>{@code snapshot}: before the commit {@code NEW}, {@code EXISTS} (an imported snapshot of the
 * date is in effect: {@code existingSnapshotId}; the commit is refused) or {@code OUT_OF_RANGE}
 * (the account's dates do not admit the date); afterwards {@code RECORDED}, and after a rollback
 * {@code DELETED} or {@code WITHDRAWN}. {@code readyToCommit} is whether a commit would succeed
 * now: the snapshot is {@code NEW}, every position resolves and no line is unreadable.
 *
 * <p>Only the cash in the account's own currency becomes the snapshot's balance ({@code recorded});
 * a statement's cash in other currencies is shown, not recorded.
 */
public record ImportHoldingsResponse(
    LocalDate snapshotDate,
    @Schema(allowableValues = {"STATEMENT", "MEMBER"}) String dateSource,
    @Schema(allowableValues = {"NEW", "EXISTS", "OUT_OF_RANGE", "RECORDED", "DELETED", "WITHDRAWN"})
        String snapshot,
    UUID existingSnapshotId,
    BigDecimal balance,
    String balanceCurrency,
    List<Position> positions,
    List<Cash> cashBalances,
    List<Unreadable> unreadable,
    int unresolvedCount,
    boolean readyToCommit) {

  public ImportHoldingsResponse {
    positions = List.copyOf(positions);
    cashBalances = List.copyOf(cashBalances);
    unreadable = List.copyOf(unreadable);
  }

  /**
   * One position: as the statement reports it, and the security it resolves to ({@code securityId}
   * while {@code RESOLVED}).
   */
  public record Position(
      int rowNumber,
      String isin,
      String symbol,
      String name,
      BigDecimal quantity,
      BigDecimal reportedPrice,
      BigDecimal reportedValue,
      String reportedCurrency,
      @Schema(allowableValues = {"RESOLVED", "UNRESOLVED"}) String resolution,
      UUID securityId,
      List<ImportRowErrorResponse> warnings) {

    public Position {
      warnings = List.copyOf(warnings);
    }
  }

  /** A cash balance; {@code recorded} when it is in the account's currency. */
  public record Cash(int rowNumber, String currency, BigDecimal amount, boolean recorded) {}

  /** A line taken for a position or a cash balance whose values could not be read. */
  public record Unreadable(int rowNumber, String line, ImportRowErrorResponse error) {}
}
