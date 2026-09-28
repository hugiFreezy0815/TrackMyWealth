package com.trackmywealth.backend.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
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
 * (FR-CAT-013). {@code categoryAssignedBy} says how it was assigned ({@code SOURCE_CODE}, {@code
 * RULE}, {@code FALLBACK_MATCH}, later {@code USER}), {@code null} for UNCATEGORIZED.
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
    String categoryAssignedBy) {}
