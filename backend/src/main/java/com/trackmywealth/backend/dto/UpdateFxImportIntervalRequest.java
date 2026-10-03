package com.trackmywealth.backend.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

/**
 * Request body for {@code PUT /api/v1/admin/fx-import} (US-06-07, #227). Only a value outside the
 * allowed intervals (1, 2, 6, 12 or 24 hours) is a 422; a missing one is a 400.
 */
public record UpdateFxImportIntervalRequest(
    @NotNull @Schema(allowableValues = {"1", "2", "6", "12", "24"}) Integer intervalHours) {}
