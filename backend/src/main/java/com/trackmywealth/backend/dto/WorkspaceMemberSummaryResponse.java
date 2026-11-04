package com.trackmywealth.backend.dto;

import java.time.LocalDate;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** Returned by every {@code /api/v1/workspace-members/**} endpoint - never the entity. */
public record WorkspaceMemberSummaryResponse(
    UUID id,
    String displayName,
    String status,
    LocalDate memberSince,
    @Nullable LocalDate memberUntil,
    int version) {}
