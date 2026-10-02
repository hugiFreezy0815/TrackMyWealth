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
 * Request body for {@code PUT /api/v1/accounts/{accountId}/transactions/{transactionId}} (US-07-06,
 * FR-LIF-004): the transaction's full editable state, typically the row as read with the wanted
 * changes applied. Every field means what it means on {@link CreateTransactionRequest}; the type,
 * idempotency key, MCC and transfer counterparty are not editable.
 *
 * <p>The server compares the request with the stored row and decides how to apply it - the member
 * does not choose (FR-LIF-002b):
 *
 * <ul>
 *   <li>Only {@code merchantDescription} and {@code notes} differ: a normal in-place update.
 *   <li>Any financial field differs ({@code accountId}, {@code bookingDate}, {@code amount}, {@code
 *       currency}, the FX rate, {@code feeAmount} or an investment field): the original is removed
 *       as its provenance requires - soft-deleted when manual, voided when imported, which needs a
 *       {@code reason} (422 without one) - and a replacement with the requested values is recorded,
 *       validated as a new transaction would be. Both happen atomically.
 * </ul>
 *
 * <p>{@code accountId} moves the transaction to another account of the workspace (EDIT on both).
 * The FX rate counts as changed only when {@code fxRateToAccountCurrency} or {@code billedAmount}
 * gives a different one; sending neither keeps the original's rate when nothing else changes, and
 * otherwise lets the replacement's rate be resolved as when recording. Sending back an estimated
 * rate unchanged (PR-011) never turns it into a disclosed one: the replacement's rate is estimated
 * again for its own values.
 */
public record UpdateTransactionRequest(
    @NotNull UUID accountId,
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
    String reason) {

  public UpdateTransactionRequest {
    currency = RequestStrings.blankToNull(currency);
    merchantDescription = RequestStrings.blankToNull(merchantDescription);
    notes = RequestStrings.blankToNull(notes);
    reason = RequestStrings.blankToNull(reason);
  }
}
