package com.trackmywealth.backend.dto;

import jakarta.validation.constraints.NotNull;
import java.util.UUID;

/** Parses (again) an import batch's stored file with the current version of a template. */
public record ImportParseRequest(@NotNull UUID templateId) {}
