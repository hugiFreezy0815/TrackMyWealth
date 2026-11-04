package com.trackmywealth.backend.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * One cash-scope reconciliation result in an account's history (US-25-02).
 *
 * @param resolutionNote the latest member decision's reason (US-25-03); kept when the result is
 *     reopened, so the history still says why it had been accepted or dismissed
 * @param resolutionTransactionId the visible adjusting {@code VALUATION_ADJUSTMENT} entry of an
 *     {@code ACCEPTED} result; null in every other status
 * @param version the row revision a decision sends back in {@code If-Match} (ADR 0004)
 * @param finalized {@code true} for an {@code ACCEPTED} or {@code DISMISSED} result a newer
 *     snapshot has overtaken: it is history and can no longer be reopened; a correction goes into
 *     the newest comparison
 */
public record ReconciliationResultResponse(
    UUID id,
    UUID accountId,
    UUID snapshotId,
    LocalDate asOf,
    @Nullable BigDecimal differenceAmount,
    String currency,
    @Nullable String probableCause,
    String status,
    @Nullable OffsetDateTime resolvedAt,
    OffsetDateTime createdAt,
    @Nullable String resolutionNote,
    @Nullable UUID resolutionTransactionId,
    int version,
    boolean finalized) {}
