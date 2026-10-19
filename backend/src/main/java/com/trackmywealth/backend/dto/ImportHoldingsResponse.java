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
 * <p>US-07-31 (#366): the preview says exactly what the commit will do to the imported snapshot of
 * the statement's date. {@code snapshot} before the commit: {@code NEW}, {@code COMPLETES} (adds
 * the parts in {@code adds} to {@code existingSnapshot}), {@code ALREADY_RECORDED} (a duplicate:
 * discard it), {@code CONFLICTING} (differs as {@code conflicts} lists; nothing is overwritten) or
 * {@code OUT_OF_RANGE}; afterwards {@code RECORDED} or {@code COMPLETED}, and after a rollback
 * {@code DELETED}, {@code WITHDRAWN} or {@code REMOVED} (its parts taken out of a snapshot another
 * import keeps). {@code explanation} is one sentence to show above the preview, in the caller's
 * language; what keeps the commit back comes first. {@code readyToCommit} is whether a commit would
 * succeed now: {@code existingSnapshot.reconciliationResultId} names a reconciliation decision that
 * keeps a {@code COMPLETES} statement back until it is reopened.
 *
 * <p>While a position resolves to no security, or two resolve to the same one, the statement's
 * holdings are not compared with the snapshot's yet (the position resolving later may be the one
 * the snapshot holds): {@code conflicts} then lists no position and an {@code ALREADY_RECORDED} is
 * no duplicate verdict, {@code readyToCommit} is false and {@code explanation} names what is
 * missing. Once every position resolves, the preview compares them.
 *
 * <p>Only the cash in the account's own currency becomes the snapshot's balance ({@code recorded});
 * a statement's cash in other currencies is shown, not recorded.
 */
public record ImportHoldingsResponse(
    LocalDate snapshotDate,
    @Schema(allowableValues = {"STATEMENT", "MEMBER"}) String dateSource,
    @Schema(
            allowableValues = {
              "NEW",
              "COMPLETES",
              "ALREADY_RECORDED",
              "CONFLICTING",
              "OUT_OF_RANGE",
              "RECORDED",
              "COMPLETED",
              "DELETED",
              "WITHDRAWN",
              "REMOVED"
            })
        String snapshot,
    ImportSnapshotPlan.Existing existingSnapshot,
    @Schema(allowableValues = {"BALANCE", "HOLDINGS"}) List<String> adds,
    List<ImportSnapshotPlan.Conflict> conflicts,
    String explanation,
    BigDecimal balance,
    String balanceCurrency,
    List<Position> positions,
    List<Cash> cashBalances,
    List<Unreadable> unreadable,
    int unresolvedCount,
    boolean readyToCommit) {

  public ImportHoldingsResponse {
    adds = List.copyOf(adds);
    conflicts = List.copyOf(conflicts);
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
