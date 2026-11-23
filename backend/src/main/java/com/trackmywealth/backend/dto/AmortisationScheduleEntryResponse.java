package com.trackmywealth.backend.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * One entry of a loan's or mortgage's amortisation schedule (US-10-02). {@code currency} is the
 * loan's own; {@code version} is the token {@code If-Match} expects on a replace or delete (ADR
 * 0004).
 *
 * @param amount the instalment due, {@code interestComponent + principalComponent} (#693), so a
 *     client shows the payment total without adding it up
 */
public record AmortisationScheduleEntryResponse(
    UUID id,
    UUID accountId,
    LocalDate dueDate,
    BigDecimal interestComponent,
    BigDecimal principalComponent,
    BigDecimal amount,
    BigDecimal remainingBalance,
    String currency,
    int version,
    OffsetDateTime createdAt) {

  public AmortisationScheduleEntryResponse {
    // An idempotent create replays the body stored before #693, which has no amount.
    if (amount == null) {
      amount = interestComponent.add(principalComponent);
    }
  }
}
