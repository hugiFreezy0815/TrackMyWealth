package com.trackmywealth.backend.dto;

import com.trackmywealth.backend.validation.ValidCurrencyCode;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Request body for {@code POST /api/v1/accounts/{accountId}/transactions} (US-09-01).
 *
 * <p>{@code amount} is cash-direction signed and stored as sent (see {@link
 * com.trackmywealth.backend.entity.Transaction}): a card purchase of CHF 85.00 is {@code -85.00}.
 * The API does not silently flip a sign, so what the caller sends is what the ledger records.
 * {@code TransactionService} rejects a non-negative amount for a purchase.
 *
 * <p>Only {@code CREDIT_CARD_PURCHASE} is accepted so far - the wider set of ledger types belongs
 * to US-07-01, which widens this request rather than replacing it, so {@code transactionType} is
 * validated in the service (where the account is known) rather than pinned by a pattern here.
 * {@code mcc} is the ISO 18245 Merchant Category Code, four digits (FR-CC-002).
 *
 * <p>Every optional {@code String} field is normalized blank-to-{@code null} in the compact
 * constructor via {@link RequestStrings#blankToNull}.
 */
public record CreateTransactionRequest(
    @NotBlank String transactionType,
    @NotNull LocalDate bookingDate,
    @NotNull @Digits(integer = 16, fraction = 4) BigDecimal amount,
    @NotBlank @ValidCurrencyCode String currency,
    @Size(max = 255) String merchantDescription,
    @Pattern(regexp = "\\d{4}", message = "mcc must be a four-digit ISO 18245 code") String mcc,
    @Size(max = 2000) String notes) {

  public CreateTransactionRequest {
    transactionType = RequestStrings.blankToNull(transactionType);
    currency = RequestStrings.blankToNull(currency);
    merchantDescription = RequestStrings.blankToNull(merchantDescription);
    mcc = RequestStrings.blankToNull(mcc);
    notes = RequestStrings.blankToNull(notes);
  }
}
