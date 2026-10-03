package com.trackmywealth.backend.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * An account's opening balance (US-25-04, FR-REC-007). Stored as the account's one {@code
 * account_snapshot} with {@code is_opening_balance}; {@code id} is that snapshot's id, so it also
 * appears in {@code GET /accounts/{id}/snapshots}.
 *
 * @param warnings the account's data-quality warnings ({@link DataQualityWarningValues}) as they
 *     stand after this opening balance - {@code TRANSACTIONS_BEFORE_OPENING_BALANCE} when earlier
 *     rows were acknowledged
 */
public record OpeningBalanceResponse(
    UUID id,
    UUID accountId,
    LocalDate date,
    BigDecimal balance,
    String currency,
    OffsetDateTime createdAt,
    OffsetDateTime updatedAt,
    int version,
    List<String> warnings) {

  public OpeningBalanceResponse {
    // Defensive/immutable copy (SpotBugs EI_EXPOSE_REP), same as AccountSnapshotResponse.
    warnings = List.copyOf(warnings);
  }
}
