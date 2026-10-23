package com.trackmywealth.backend.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.trackmywealth.backend.validation.ValidWorkspaceName;

/**
 * US-03-07: {@code name} must be present; an explicit null or blank value clears the workspace
 * name. Required, so that a missing or misspelled field is a 400 instead of a silent clear.
 */
public record UpdateWorkspaceNameRequest(
    @JsonProperty(required = true) @ValidWorkspaceName String name) {}
