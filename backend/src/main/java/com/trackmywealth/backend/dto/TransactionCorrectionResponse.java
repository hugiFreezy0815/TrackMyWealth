package com.trackmywealth.backend.dto;

/**
 * Result of correcting a transaction (US-07-06).
 *
 * <p>The transaction is the effective row after the operation. Removal is null for a text-only edit
 * and contains the old-row lifecycle result for a financial correction. Version is the effective
 * row version returned as the response ETag.
 *
 * <p>After a financial correction the effective row is the replacement, a new row with its own id:
 * the URL the correction was sent to now names the removed original. Clients continue with {@code
 * transaction.id()} and this version, e.g. as {@code If-Match} on the next edit of the replacement.
 */
public record TransactionCorrectionResponse(
    int version, TransactionResponse transaction, TransactionRemovalResponse removal) {}
