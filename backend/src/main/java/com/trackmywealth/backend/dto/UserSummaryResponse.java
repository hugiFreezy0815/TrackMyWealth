package com.trackmywealth.backend.dto;

import java.util.UUID;

/** Returned by every {@code /api/v1/admin/users/**} endpoint (US-02-01) - never the entity. */
public record UserSummaryResponse(
    UUID id, String email, String role, String status, String language) {}
