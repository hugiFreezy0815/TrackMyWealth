package com.trackmywealth.backend.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * One immutable audit event (US-07-38). Kinds are intentionally open to future ledger event kinds.
 * Category ids are resolved to localized names by the client; no raw bank data is exposed.
 */
public record TransactionHistoryEntryResponse(
    OffsetDateTime occurredAt,
    @Schema(
            description =
                "Open set; a client shows an unknown kind generically. Known: IMPORTED, CREATED,"
                    + " CATEGORY_ASSIGNED (automatic, or an older reset), CATEGORY_CHANGED (a"
                    + " member's choice), CATEGORY_RESET (a member's reset to automatic),"
                    + " CORRECTED, VOIDED, DELETED, RESTORED.")
        String kind,
    @Schema(description = "The workspace member's display name; null for a system step.")
        @Nullable String actorDisplayName,
    @Schema(
            description =
                "Category kinds: the category before. Null when the row had no category, e.g. a"
                    + " type that is never categorized.")
        @Nullable UUID fromCategoryId,
    @Schema(description = "Category kinds: the category after.") @Nullable UUID toCategoryId,
    @Schema(
            description =
                "CORRECTED: the row this one corrects. VOIDED: the reversing entry. RESTORED: the"
                    + " voided row this copy restores.")
        @Nullable UUID relatedTransactionId,
    @Schema(description = "IMPORTED: the import the row came from.")
        @Nullable TransactionImportBatchResponse importBatch,
    @Schema(description = "VOIDED: the reason given.") @Nullable String reason) {}
