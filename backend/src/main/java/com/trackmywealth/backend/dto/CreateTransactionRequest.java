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
 * <p>{@code externalId} is an optional client-generated idempotency key (a UUID is ideal), stored
 * in {@code transaction.external_id} under source {@code MANUAL}. The ledger is append-only, so a
 * duplicate purchase cannot be edited away - it can only be voided - and a mobile client retrying a
 * request whose response was lost would otherwise double the debt. With a key, a retry returns the
 * originally recorded row instead of inserting a second one; the same key with different financial
 * fields is rejected (409) rather than silently returning a row that does not match the request.
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
    @Size(max = 2000) String notes,
    @Size(max = 255) String externalId) {

  public CreateTransactionRequest {
    transactionType = RequestStrings.blankToNull(transactionType);
    currency = RequestStrings.blankToNull(currency);
    merchantDescription = RequestStrings.blankToNull(merchantDescription);
    mcc = RequestStrings.blankToNull(mcc);
    notes = RequestStrings.blankToNull(notes);
    externalId = RequestStrings.blankToNull(externalId);
  }
}
