package com.trackmywealth.backend.dto;

import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** Workspace metadata visible to a system administrator. */
public record AdminWorkspaceSummaryResponse(UUID id, @Nullable String name) {}
