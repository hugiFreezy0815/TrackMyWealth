package com.trackmywealth.backend.dto;

import java.util.UUID;

/**
 * US-07-11: a template version's dry run on a file without an account: the template's catalogue
 * institution ({@code null} for a template not tied to one), its definition and what it read.
 * Internal, not part of any API response.
 */
public record ImportTemplateParse(
    UUID institutionCatalogueId, ImportTemplateDefinition definition, ImportParseResult result) {}
