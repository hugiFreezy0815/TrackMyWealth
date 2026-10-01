package com.trackmywealth.backend.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * An account snapshot and its holdings (US-25-01). {@code balance} is {@code null} for a snapshot
 * that reported positions only; {@code updatedAt} is {@code null} until a manual snapshot is first
 * replaced.
 */
public record AccountSnapshotResponse(
    UUID id,
    UUID accountId,
    LocalDate snapshotDate,
    BigDecimal balance,
    String currency,
    String source,
    boolean openingBalance,
    OffsetDateTime createdAt,
    OffsetDateTime updatedAt,
    int version,
    List<SnapshotHoldingResponse> holdings) {

  public AccountSnapshotResponse {
    // Defensive/immutable copy (SpotBugs EI_EXPOSE_REP), same as SecurityCreation.
    holdings = List.copyOf(holdings);
  }
}
