package com.trackmywealth.backend.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * US-07-12: the balance an import file states, and what its commit makes of it.
 *
 * <p>{@code outcome} is {@code FOUND}, {@code OUT_OF_RANGE} (read, but dated in the future, before
 * the account was opened or after it was closed: the commit records no snapshot), {@code NOT_FOUND}
 * (no line in the account's currency matched), {@code UNREADABLE} (a line that may be in the
 * account's currency matched, but its date, amount or currency could not be read) or {@code
 * AMBIGUOUS} (two different balances on the latest date). Only a {@code FOUND} or {@code
 * OUT_OF_RANGE} balance has {@code date}, {@code balance} and {@code currency}: the statement's
 * closing balance, in the account's currency and with a snapshot's sign (a liability's is the
 * positive amount owed). {@code openingDate}/{@code openingBalance} are the earliest other balance
 * the file states, shown only: it never becomes the account's opening balance by itself.
 *
 * <p>{@code snapshot}: before the commit, {@code NEW} (it will be recorded), {@code
 * ALREADY_RECORDED} or {@code CONFLICTING} (an imported snapshot of that date exists, with the same
 * or another balance: it will not be); afterwards, {@code RECORDED}, {@code ALREADY_RECORDED} or
 * {@code CONFLICTING}, and after a rollback {@code DELETED} or {@code WITHDRAWN}. US-07-31: {@code
 * COMPLETES}/{@code COMPLETED} when the balance completes an imported snapshot of its date that has
 * none (a holdings statement's), and {@code REMOVED} when a rollback took it out of a snapshot
 * another import keeps. Before the commit, {@code existingSnapshot}, {@code adds} and {@code
 * conflicts} say what the balance meets, as for a holdings statement ({@link
 * ImportHoldingsResponse}); {@code explanation} is one sentence about the balance in the caller's
 * language, before and after the commit, {@code null} while there is no {@code snapshot}. Whatever
 * the balance meets, the statement's bookings are committed.
 */
public record ImportStatementBalanceResponse(
    @Schema(allowableValues = {"FOUND", "OUT_OF_RANGE", "NOT_FOUND", "UNREADABLE", "AMBIGUOUS"})
        String outcome,
    LocalDate date,
    BigDecimal balance,
    String currency,
    LocalDate openingDate,
    BigDecimal openingBalance,
    @Schema(
            allowableValues = {
              "NEW",
              "COMPLETES",
              "ALREADY_RECORDED",
              "CONFLICTING",
              "RECORDED",
              "COMPLETED",
              "DELETED",
              "WITHDRAWN",
              "REMOVED"
            })
        String snapshot,
    ImportSnapshotPlan.Existing existingSnapshot,
    @Schema(allowableValues = {"BALANCE"}) List<String> adds,
    List<ImportSnapshotPlan.Conflict> conflicts,
    String explanation) {

  public ImportStatementBalanceResponse {
    adds = List.copyOf(adds);
    conflicts = List.copyOf(conflicts);
  }
}
