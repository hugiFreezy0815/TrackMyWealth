package com.trackmywealth.backend.dto;

/**
 * Result of correcting a transaction (US-07-06).
 *
 * <p>The transaction is the effective row after the operation. Removal is null for a text-only edit
 * and contains the old-row lifecycle result for a financial correction. Version is the effective
 * row version returned as the response ETag.
 */
public record TransactionCorrectionResponse(
    int version, TransactionResponse transaction, TransactionRemovalResponse removal) {}
