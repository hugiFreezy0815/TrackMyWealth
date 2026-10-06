package com.trackmywealth.backend.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * A warning on an import batch (US-07-04): exactly this file (same SHA-256) was already committed
 * to the account, by batch {@code batchId} at {@code committedAt}.
 */
public record ImportSameFileResponse(UUID batchId, OffsetDateTime committedAt) {}
