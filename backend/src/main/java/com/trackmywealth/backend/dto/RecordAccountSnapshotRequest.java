package com.trackmywealth.backend.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Request body for {@code POST /api/v1/accounts/{accountId}/snapshots} (US-25-01): the balance, and
 * for a position-holding account the positions, as printed on a statement.
 *
 * <p>No {@code currency} and no {@code source}: the currency is the account's own ({@code
 * native_currency}, or a credit card's {@code billing_currency}; V58 enforces it) and an
 * API-entered snapshot is always {@code MANUAL}. {@code balance} uses the same convention as {@code
 * GET /accounts/{id}/balance} (a liability's balance is the positive amount owed) and may be
 * omitted only when {@code holdings} are given. See {@code AccountSnapshotService} for the
 * remaining rules.
 */
public record RecordAccountSnapshotRequest(
    @NotNull LocalDate snapshotDate,
    @Digits(integer = 16, fraction = 4) BigDecimal balance,
    @Size(max = 500) List<@Valid SnapshotHoldingRequest> holdings) {

  public RecordAccountSnapshotRequest {
    // List.copyOf (SpotBugs EI_EXPOSE_REP) rejects a null element while the body is bound, which
    // the caller sees as a 400 like any other malformed body.
    holdings = holdings == null ? List.of() : List.copyOf(holdings);
  }
}
