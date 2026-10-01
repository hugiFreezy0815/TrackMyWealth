package com.trackmywealth.backend.dto;

import java.util.UUID;

/**
 * A card's statement-cycle configuration (US-09-03, FR-CC-008). Either field may be {@code null}.
 */
public record StatementConfigResponse(
    UUID cardAccountId, Integer statementDay, Integer dueDateOffsetDays, int version) {}
