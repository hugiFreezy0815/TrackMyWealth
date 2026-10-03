package com.trackmywealth.backend.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

/** One cash-scope reconciliation result in an account's history (US-25-02). */
public record ReconciliationResultResponse(
    UUID id,
    UUID accountId,
    UUID snapshotId,
    LocalDate asOf,
    BigDecimal differenceAmount,
    String currency,
    String probableCause,
    String status,
    OffsetDateTime resolvedAt,
    OffsetDateTime createdAt) {}
