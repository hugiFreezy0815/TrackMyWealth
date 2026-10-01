package com.trackmywealth.backend.dto;

/**
 * Result of correcting a transaction (US-07-06).
 *
 * <p>{@code transaction} is the effective row after the operation: the same row for a
 * non-financial edit, or the newly inserted replacement for a financial correction. {@code
 * removal} is null for an in-place edit; otherwise it records how the old row and any linked rows
 * were removed/voided. {@code version} is the version of {@code transaction} and is also returned
 * as the response ETag.
 */
public record TransactionCorrectionResponse(
    int version, TransactionResponse transaction, TransactionRemovalResponse removal) {}
