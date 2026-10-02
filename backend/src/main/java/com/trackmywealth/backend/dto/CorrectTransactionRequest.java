package com.trackmywealth.backend.dto;

import com.trackmywealth.backend.validation.ValidCurrencyCode;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Desired state for correcting a transaction (US-07-06 / FR-LIF-004).
 *
 * <p>This request is separate from {@link CreateTransactionRequest}: callers cannot change source
 * provenance or supply a new source idempotency key. The body supplies the desired financial state.
 * Merchant description and notes may be edited in place; financial changes create a replacement.
 *
 * <p>Target account is optional and defaults to the current account. Reason is required only when
 * the original is imported (T2), because that correction voids the original. The body is the
 * desired state: an omitted FX rate, fee or counterparty means "none" (or "derive it", for the FX
 * rate), not "unchanged". The one exception is {@code mcc}: omitted keeps the original's MCC, which
 * is source data; a different MCC is corrected like a financial field (a replacement carries it).
 *
 * <p>The category is not part of a correction: it is set and reset on {@code PUT/DELETE
 * .../category} (US-08-02), and a member's override carries over to a replacement whose type is
 * categorized, while its category is still assignable.
 */
public record CorrectTransactionRequest(
    UUID targetAccountId,
    @NotBlank String transactionType,
    @NotNull LocalDate bookingDate,
    @NotNull @Digits(integer = 16, fraction = 4) BigDecimal amount,
    @NotBlank @ValidCurrencyCode String currency,
    @Size(max = 255) String merchantDescription,
    @Pattern(regexp = "\\d{4}", message = "{tmw.validation.mcc}") String mcc,
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
    mcc = RequestStrings.blankToNull(mcc);
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
        mcc,
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

  /** The same desired state with no FX rate: "let the server derive it". */
  public CorrectTransactionRequest withoutFxRate() {
    return new CorrectTransactionRequest(
        targetAccountId,
        transactionType,
        bookingDate,
        amount,
        currency,
        merchantDescription,
        mcc,
        notes,
        null,
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
        counterpartyAmount,
        reason);
  }
}
