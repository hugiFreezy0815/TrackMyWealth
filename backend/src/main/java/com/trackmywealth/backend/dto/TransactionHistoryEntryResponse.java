package com.trackmywealth.backend.dto;

import java.time.OffsetDateTime;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * One immutable audit event. Kinds are intentionally open to future ledger event kinds.
 * Category ids are resolved to localized names by the client; no raw bank data is exposed.
 */
public record TransactionHistoryEntryResponse(
    OffsetDateTime occurredAt,
    String kind,
    @Nullable String actorDisplayName,
    @Nullable UUID fromCategoryId,
    @Nullable UUID toCategoryId,
    @Nullable UUID relatedTransactionId,
    @Nullable TransactionImportBatchResponse importBatch,
    @Nullable String reason) {}
