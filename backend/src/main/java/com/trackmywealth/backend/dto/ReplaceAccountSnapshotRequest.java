package com.trackmywealth.backend.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.List;

/**
 * Request body for {@code PUT /api/v1/accounts/{accountId}/snapshots/{snapshotId}} (US-25-01's
 * "update today's snapshot"): a full replacement of a {@code MANUAL} snapshot's balance and
 * holdings. The date is the snapshot's identity and is not part of the body; a snapshot for another
 * date is a new snapshot. Same field rules as {@link RecordAccountSnapshotRequest}.
 */
public record ReplaceAccountSnapshotRequest(
    @Digits(integer = 16, fraction = 4) BigDecimal balance,
    @Size(max = 500) List<@Valid SnapshotHoldingRequest> holdings) {

  public ReplaceAccountSnapshotRequest {
    // List.copyOf (SpotBugs EI_EXPOSE_REP) rejects a null element while the body is bound, which
    // the caller sees as a 400 like any other malformed body.
    holdings = holdings == null ? List.of() : List.copyOf(holdings);
  }
}
