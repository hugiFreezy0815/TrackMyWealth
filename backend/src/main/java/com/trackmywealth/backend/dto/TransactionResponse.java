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
    OffsetDateTime createdAt) {}
