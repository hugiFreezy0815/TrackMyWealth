package com.trackmywealth.backend.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * US-07-31 (#366): what committing a statement would do to the imported snapshot of its account and
 * date, part by part (see {@link ImportSnapshotValues}).
 *
 * @param outcome {@code NEW}, {@code COMPLETES}, {@code ALREADY_RECORDED}, {@code CONFLICTING} or
 *     {@code OUT_OF_RANGE}; only {@code NEW} and {@code COMPLETES} can be committed
 * @param existing the imported snapshot of that date in effect, {@code null} for {@code NEW} and
 *     {@code OUT_OF_RANGE}; with the reconciliation decision that keeps a {@code COMPLETES} plan
 *     from being committed, if one stands
 * @param adds the parts the commit would record: {@code BALANCE}, {@code HOLDINGS}
 * @param conflicts each part (or position) in which the statement differs from the snapshot
 */
public record ImportSnapshotPlan(
    @Schema(
            allowableValues = {
              "NEW",
              "COMPLETES",
              "ALREADY_RECORDED",
              "CONFLICTING",
              "OUT_OF_RANGE"
            })
        String outcome,
    Existing existing,
    List<String> adds,
    List<Conflict> conflicts) {

  public ImportSnapshotPlan {
    adds = List.copyOf(adds);
    conflicts = List.copyOf(conflicts);
  }

  /** Whether a commit may record this plan, unless a decision stands ({@link #decided}). */
  public boolean committable() {
    return ImportSnapshotValues.NEW.equals(outcome)
        || ImportSnapshotValues.COMPLETES.equals(outcome);
  }

  /**
   * Whether a member's reconciliation decision stands on the snapshot: completing it is then a 409
   * {@code IMPORT_SNAPSHOT_RECONCILIATION_DECIDED}.
   */
  public boolean decided() {
    return existing != null && existing.reconciliationResultId() != null;
  }

  /**
   * The imported snapshot a statement meets: its id, the batch that recorded it and that batch's
   * file name, which parts it has, and the reconciliation result whose decision stands on it
   * ({@code null} for none; a final decision on an older balance does not stand in the way).
   */
  public record Existing(
      UUID id,
      UUID importBatchId,
      String importFileName,
      BigDecimal balance,
      int holdingsCount,
      UUID reconciliationResultId) {}

  /**
   * One difference. For {@code BALANCE}: the two balances. For {@code HOLDING}: one security's
   * quantity in the snapshot and in the statement, either {@code null} when only one side holds it.
   */
  public record Conflict(
      @Schema(allowableValues = {"BALANCE", "HOLDING"}) String part,
      UUID securityId,
      String isin,
      String securityName,
      BigDecimal existingValue,
      BigDecimal statementValue) {}
}
