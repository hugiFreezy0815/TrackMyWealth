package com.trackmywealth.backend.dto;

import java.util.UUID;

/** Re-parses a stored file. For CAMT XML, templateId may be null. */
public record ImportParseRequest(UUID templateId) {}
