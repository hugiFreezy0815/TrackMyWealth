package com.trackmywealth.backend.dto;

import java.util.UUID;

/** Current workspace settings exposed by {@code GET /api/v1/workspace} (US-06-05). */
public record WorkspaceResponse(UUID id, String name, String currency, int version) {}
