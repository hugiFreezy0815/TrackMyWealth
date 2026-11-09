package com.trackmywealth.backend.dto;

import java.time.OffsetDateTime;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * One row of {@code TransactionHistoryRepository} (US-07-38): an event as the database knows it,
 * with the import batch's raw template format and source kind. Internal, not part of any API
 * response; the service maps it to {@link TransactionHistoryEntryResponse}. It lives here because
 * {@code ArchitectureTest} allows only repositories in {@code ..repository..}.
 */
public record TransactionHistoryEvent(
    OffsetDateTime occurredAt,
    String kind,
    @Nullable String actorDisplayName,
    @Nullable UUID fromCategoryId,
    @Nullable UUID toCategoryId,
    @Nullable UUID relatedTransactionId,
    @Nullable UUID importBatchId,
    @Nullable OffsetDateTime importUploadedAt,
    @Nullable String importTemplateFormat,
    @Nullable String importSourceKind,
    @Nullable String reason) {}
