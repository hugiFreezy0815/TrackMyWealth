package com.trackmywealth.backend.dto;

import com.trackmywealth.backend.validation.ValidWorkspaceName;

/** A null or blank name clears the workspace label (US-03-07). */
public record UpdateWorkspaceNameRequest(@ValidWorkspaceName String name) {}
