package com.trackmywealth.backend.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * A ledger row as returned by the API (US-09-01). {@code amount} is cash-direction signed, exactly
 * as stored. {@code mcc} is the source-provided Merchant Category Code, {@code null} when the
 * source had none - it is independent of any reporting category (FR-CC-002/RULE-011).
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
    OffsetDateTime createdAt) {}
