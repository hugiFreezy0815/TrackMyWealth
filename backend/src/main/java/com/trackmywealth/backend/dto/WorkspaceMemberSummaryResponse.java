package com.trackmywealth.backend.dto;

import java.time.LocalDate;
import java.util.UUID;

/** Returned by every {@code /api/v1/workspace-members/**} endpoint - never the entity. */
public record WorkspaceMemberSummaryResponse(
    UUID id,
    String displayName,
    boolean dependent,
    String status,
    LocalDate memberSince,
    LocalDate memberUntil,
    int version) {}
