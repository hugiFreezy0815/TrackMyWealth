package com.trackmywealth.backend.dto;

import java.util.UUID;

/**
 * A template version a member may import with (US-07-03 for US-07-04): the exact version {@code id}
 * and {@code templateVersion} an import batch records (FR-IMP-023), and the {@code definition} to
 * hand to the parser.
 */
public record ResolvedImportTemplate(
    UUID id, String templateVersion, ImportTemplateDefinition definition) {}
