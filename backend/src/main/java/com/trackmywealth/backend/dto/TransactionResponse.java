package com.trackmywealth.backend.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * A ledger row as returned by the API (US-09-01). {@code amount} is cash-direction signed, exactly
 * as stored. {@code mcc} is the source-provided Merchant Category Code, {@code null} when the
 * source had none - it is independent of any reporting category (FR-CC-002/RULE-011). {@code
 * externalId} is the idempotency key the caller supplied, {@code null} if none. {@code
 * internalTransfer} is {@code true} once settlement matching has linked the row as one leg of a
 * transfer between the caller's own accounts (US-09-02) - such a row is not income or an expense.
 *
 * <p>US-09-04: {@code fxRateToAccountCurrency} is the rate that converts {@code amount} (in {@code
 * currency}) into the account's own currency by multiplication - {@code null} for an ordinary
 * same-currency row. {@code fxRateEstimated} is {@code true} when that rate is a generic daily-rate
 * fallback rather than issuer-disclosed or source-derived (PR-011). {@code relatedTransactionId}
 * links a distinct {@code FEE} row back to the purchase it was charged on - {@code null} on the
 * purchase itself; list the ledger to find a purchase's fee row, if any.
 *
 * <p>US-07-01: the investment fields ({@code securityId} through {@code netAmount}) are {@code
 * null} except on a {@code BUY}, {@code SELL} or {@code DIVIDEND}, with the meaning described on
 * {@link CreateTransactionRequest}; {@code netAmount} is set (equal to {@code amount}) when a
 * dividend's gross and withheld amounts were given.
 *
 * <p>US-08-01: {@code categoryId} is the reporting category - {@code null} for a type that is not
 * categorized (settlement, trade, dividend), the shipped UNCATEGORIZED default when nothing matched
 * (FR-CAT-013). {@code categoryAssignedBy} says how it was assigned ({@code RULE}, {@code
 * SOURCE_CODE}, {@code TRANSACTION_TYPE}, {@code FALLBACK_MATCH}, later {@code USER}), {@code null}
 * for UNCATEGORIZED.
 *
 * <p>US-07-02: {@code removal} is how this row would be removed ({@code SOFT_DELETE} for a manual
 * row, {@code VOID} for an imported one, see {@link TransactionRemovalValues}), {@code null} when
 * it cannot be removed any more (already voided, a reversing row, or soft-deleted). A voided
 * original stays listed with {@code voidedAt} and {@code voidReason} (FR-LIF-003); its reversing
 * row points back at it through {@code replacesTransactionId}. Restoring a void (US-07-07) keeps
 * both and adds an ordinary copy of the original, whose {@code restoresTransactionId} names it. A
 * correction replacement points to the prior ledger row through {@code correctsTransactionId};
 * correction chains therefore remain explicit without overloading reversal semantics. {@code
 * deletedAt} is set only in the restore list of soft-deleted rows.
 *
 * <p>US-25-03 ({@code reconciliationAdjustment}): on a {@code VALUATION_ADJUSTMENT} row, where it
 * stands in its reconciliation result's lifecycle ({@link ReconciliationAdjustmentValues}) - so a
 * client can say why the row is locked and what still works; {@code null} on every other row. Such
 * a row has no {@code merchantDescription}: the client labels it in its own language, and {@code
 * notes} carries the member's reason.
 *
 * <p>US-25-04 ({@code warnings}): the row's data-quality warnings ({@link
 * DataQualityWarningValues}), empty when there are none - {@code BOOKED_BEFORE_OPENING_BALANCE}
 * when it is booked before its account's opening balance and therefore left out of the account's
 * value.
 */
public record TransactionResponse(
    UUID id,
    UUID accountId,
    String transactionType,
    LocalDate bookingDate,
    BigDecimal amount,
    String currency,
    String merchantDescription,
    String mcc,
    String notes,
    String source,
    String externalId,
    boolean internalTransfer,
    BigDecimal fxRateToAccountCurrency,
    boolean fxRateEstimated,
    UUID relatedTransactionId,
    OffsetDateTime createdAt,
    UUID securityId,
    BigDecimal quantity,
    BigDecimal unitPrice,
    BigDecimal feeAmount,
    LocalDate tradeDate,
    LocalDate settlementDate,
    BigDecimal grossAmount,
    BigDecimal taxWithheldAmount,
    BigDecimal netAmount,
    UUID categoryId,
    String categoryAssignedBy,
    String removal,
    String reconciliationAdjustment,
    OffsetDateTime voidedAt,
    String voidReason,
    UUID replacesTransactionId,
    UUID correctsTransactionId,
    UUID restoresTransactionId,
    OffsetDateTime deletedAt,
    UUID counterpartyAccountId,
    int version,
    List<String> warnings) {

  public TransactionResponse {
    // Defensive/immutable copy (SpotBugs EI_EXPOSE_REP), same as AccountValuation.
    warnings = List.copyOf(warnings);
  }
}
