package com.trackmywealth.backend.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Response body for {@code GET /api/v1/sessions} and {@code POST /api/v1/sessions/{id}/revoke}
 * (US-02-03). {@code current} marks the session the request itself was authenticated with, so a
 * client can warn "this will sign out your current device" before letting a user revoke it.
 */
public record SessionSummaryResponse(
    UUID id,
    String deviceLabel,
    String status,
    OffsetDateTime createdAt,
    OffsetDateTime lastSeenAt,
    boolean current) {}
