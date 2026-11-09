package com.trackmywealth.backend.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.trackmywealth.backend.validation.ValidWorkspaceName;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * US-03-07: {@code name} must be present; an explicit null or blank value clears the workspace
 * name. Required, so that a missing or misspelled field is a 400 instead of a silent clear.
 *
 * <p>The contract says {@code string | null} (#490), so a generated client can send the clear
 * without casting around its own types.
 */
public record UpdateWorkspaceNameRequest(
    @Schema(
            types = {"string", "null"},
            description =
                "The new name, at most 60 characters after surrounding spaces are removed; null or"
                    + " blank clears it.")
        @JsonProperty(required = true)
        @ValidWorkspaceName
        String name) {}
