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
 * <p>Which {@code transactionType}s are accepted depends on the account (a credit card takes only
 * its own two, see {@code TransactionService}), so it is validated in the service, where the
 * account is known, rather than pinned by a pattern here. US-07-01 widened the set to the cash
 * types; investment types follow with the security master. {@code mcc} is the ISO 18245 Merchant
 * Category Code, four digits (FR-CC-002).
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
 *
 * <p><b>US-09-04/FR-CC-010, US-07-01/DM-06</b>: {@code currency} may differ from the account's own
 * (a card's {@code billing_currency}) for a card purchase or a cash type - a {@code SETTLEMENT}
 * still requires an exact match. The fields below are meaningful only for such a foreign-currency
 * entry and are rejected (422) otherwise; {@code feeAmount} applies to a card purchase only:
 *
 * <ul>
 *   <li>{@code fxRateToAccountCurrency} - the issuer's applied rate, when the source discloses it
 *       directly. Mutually exclusive with {@code billedAmount}.
 *   <li>{@code billedAmount} - the billing-currency amount the source discloses instead of a rate
 *       (same sign as {@code amount}); the service derives the rate from the two.
 *   <li>{@code feeAmount} - a disclosed foreign-transaction fee, as a positive magnitude (how much
 *       was charged) rather than a signed ledger amount - the service records it as its own,
 *       correctly-signed {@code FEE} row rather than folding it into the purchase.
 * </ul>
 *
 * <p>When neither {@code fxRateToAccountCurrency} nor {@code billedAmount} is given, the service
 * falls back to {@code FxRateService}'s generic daily rate and flags the row {@code
 * fx_rate_estimated} (PR-011) - see {@code TransactionService}. It is a 422 if that fallback has no
 * rate to offer either, rather than recording a row with no derivable rate at all.
 */
public record CreateTransactionRequest(
    @NotBlank String transactionType,
    @NotNull LocalDate bookingDate,
    @NotNull @Digits(integer = 16, fraction = 4) BigDecimal amount,
    @NotBlank @ValidCurrencyCode String currency,
    @Size(max = 255) String merchantDescription,
    @Pattern(regexp = "\\d{4}", message = "mcc must be a four-digit ISO 18245 code") String mcc,
    @Size(max = 2000) String notes,
    @Size(max = 255) String externalId,
    @Digits(integer = 10, fraction = 10) BigDecimal fxRateToAccountCurrency,
    @Digits(integer = 16, fraction = 4) BigDecimal billedAmount,
    @Digits(integer = 16, fraction = 4) BigDecimal feeAmount) {

  public CreateTransactionRequest {
    transactionType = RequestStrings.blankToNull(transactionType);
    currency = RequestStrings.blankToNull(currency);
    merchantDescription = RequestStrings.blankToNull(merchantDescription);
    mcc = RequestStrings.blankToNull(mcc);
    notes = RequestStrings.blankToNull(notes);
    externalId = RequestStrings.blankToNull(externalId);
  }
}
