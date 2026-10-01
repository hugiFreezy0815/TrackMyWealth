package com.trackmywealth.backend.dto;

import com.trackmywealth.backend.validation.ValidCurrencyCode;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Desired state for correcting a transaction (US-07-06 / FR-LIF-004).
 *
 * <p>This is deliberately separate from {@link CreateTransactionRequest}: a correction cannot
 * supply a new source idempotency key or change source provenance. {@code targetAccountId} is null
 * when the row stays on its current account. The full financial state is supplied like a PUT;
 * changing only {@code merchantDescription} and/or {@code notes} is an in-place non-financial
 * edit. Any immutable financial difference goes through remove/void plus replacement.
 *
 * <p>{@code reason} is required only when the original is imported (T2), because that correction
 * voids the original. It is ignored for a manual T1 correction and for an in-place non-financial
 * edit.
 */
public record CorrectTransactionRequest(
    UUID targetAccountId,
    @NotBlank String transactionType,
    @NotNull LocalDate bookingDate,
    @NotNull @Digits(integer = 16, fraction = 4) BigDecimal amount,
    @NotBlank @ValidCurrencyCode String currency,
    @Size(max = 255) String merchantDescription,
    @Size(max = 2000) String notes,
    @Digits(integer = 10, fraction = 10) BigDecimal fxRateToAccountCurrency,
    @Digits(integer = 16, fraction = 4) BigDecimal billedAmount,
    @Digits(integer = 16, fraction = 4) BigDecimal feeAmount,
    UUID securityId,
    @Digits(integer = 18, fraction = 10) BigDecimal quantity,
    @Digits(integer = 10, fraction = 10) BigDecimal unitPrice,
    LocalDate tradeDate,
    LocalDate settlementDate,
    @Digits(integer = 16, fraction = 4) BigDecimal grossAmount,
    @Digits(integer = 16, fraction = 4) BigDecimal taxWithheldAmount,
    UUID counterpartyAccountId,
    @Digits(integer = 16, fraction = 4) BigDecimal counterpartyAmount,
    @Size(max = 500) String reason) {

  public CorrectTransactionRequest {
    transactionType = RequestStrings.blankToNull(transactionType);
    currency = RequestStrings.blankToNull(currency);
    merchantDescription = RequestStrings.blankToNull(merchantDescription);
    notes = RequestStrings.blankToNull(notes);
    reason = RequestStrings.blankToNull(reason);
  }

  /** The replacement goes through exactly the same request validation as a new transaction. */
  public CreateTransactionRequest replacementRequest() {
    return new CreateTransactionRequest(
        transactionType,
        bookingDate,
        amount,
        currency,
        merchantDescription,
        null,
        notes,
        null,
        fxRateToAccountCurrency,
        billedAmount,
        feeAmount,
        securityId,
        quantity,
        unitPrice,
        tradeDate,
        settlementDate,
        grossAmount,
        taxWithheldAmount,
        counterpartyAccountId,
        counterpartyAmount);
  }
}
