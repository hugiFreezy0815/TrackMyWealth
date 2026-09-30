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
 *
 * <p><b>US-07-01 investment types</b> ({@code BUY}, {@code SELL}, {@code DIVIDEND}, on an account
 * that holds positions) add the fields below; each is rejected (422) on any other type, and the
 * rules are checked in {@code TransactionService} so an import can reuse them:
 *
 * <ul>
 *   <li>{@code securityId} - required: an existing security-master id (create it first with {@code
 *       POST /api/v1/securities}).
 *   <li>{@code quantity} - position-direction signed: positive for a {@code BUY}, negative for a
 *       {@code SELL}; optional and positive (shares entitled) for a {@code DIVIDEND}.
 *   <li>{@code unitPrice} - positive, in {@code currency}: the trade price, or a dividend's gross
 *       amount per share. {@code amount} of a trade must equal {@code -(quantity * unitPrice +
 *       feeAmount)} within the statement's rounding: one minor unit of the currency plus half a
 *       unit in the last place of {@code unitPrice} per share. A {@code BUY}'s amount is negative;
 *       a {@code SELL}'s is whatever proceeds minus costs come to, which can be zero or negative.
 *   <li>{@code feeAmount} - on a trade, all its costs (commission, stamp duty, exchange fees) as a
 *       positive magnitude, part of the same row - unlike a card purchase's fee, whatever the
 *       currency.
 *   <li>{@code tradeDate}, {@code settlementDate} - optional on a trade, kept distinct
 *       (FR-TRX-008); settlement cannot precede trade, and {@code bookingDate} (the day the
 *       statement books the cash) cannot precede the trade either.
 *   <li>{@code grossAmount}, {@code taxWithheldAmount} - optional on a {@code DIVIDEND}, together:
 *       gross minus withheld must equal {@code amount}, the net cash received (FR-TAXR-001).
 * </ul>
 *
 * <p><b>US-10-01 transfers</b> between two of the workspace's own accounts: a {@code TRANSFER} (or
 * {@code PENSION_CONTRIBUTION}, into an account with a contribution limit) with a {@code
 * counterpartyAccountId} records both legs at once - the debit here ({@code amount} negative, in
 * this account's currency) and the credit on the counterparty, already linked as an internal
 * transfer. {@code counterpartyAmount} is the amount credited there, required when the two
 * accounts' currencies differ and rejected otherwise. A {@code TRANSFER} without a counterparty is
 * one leg of a transfer whose other side is not recorded (yet): it is matched later, or confirmed
 * as a transfer to an account not tracked here.
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
    @Digits(integer = 16, fraction = 4) BigDecimal feeAmount,
    UUID securityId,
    @Digits(integer = 18, fraction = 10) BigDecimal quantity,
    @Digits(integer = 10, fraction = 10) BigDecimal unitPrice,
    LocalDate tradeDate,
    LocalDate settlementDate,
    @Digits(integer = 16, fraction = 4) BigDecimal grossAmount,
    @Digits(integer = 16, fraction = 4) BigDecimal taxWithheldAmount,
    UUID counterpartyAccountId,
    @Digits(integer = 16, fraction = 4) BigDecimal counterpartyAmount) {

  public CreateTransactionRequest {
    transactionType = RequestStrings.blankToNull(transactionType);
    currency = RequestStrings.blankToNull(currency);
    merchantDescription = RequestStrings.blankToNull(merchantDescription);
    mcc = RequestStrings.blankToNull(mcc);
    notes = RequestStrings.blankToNull(notes);
    externalId = RequestStrings.blankToNull(externalId);
  }
}
