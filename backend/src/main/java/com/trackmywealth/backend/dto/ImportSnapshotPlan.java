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
 *     {@code OUT_OF_RANGE}
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

  /** Whether a commit may record this plan. */
  public boolean committable() {
    return ImportSnapshotValues.NEW.equals(outcome)
        || ImportSnapshotValues.COMPLETES.equals(outcome);
  }

  /**
   * The imported snapshot a statement meets: its id, the batch that recorded it, and which parts it
   * has.
   */
  public record Existing(UUID id, UUID importBatchId, BigDecimal balance, int holdingsCount) {}

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
