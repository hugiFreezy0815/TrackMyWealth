package com.trackmywealth.backend.dto;

import java.util.UUID;

/**
 * Parses (again) an import batch's stored file with the current version of a template, or - for a
 * CAMT XML statement or ZIP archive of them, which no template reads (#350) - without {@code
 * templateId}. Any other file without one is a 400.
 */
public record ImportParseRequest(UUID templateId) {}
