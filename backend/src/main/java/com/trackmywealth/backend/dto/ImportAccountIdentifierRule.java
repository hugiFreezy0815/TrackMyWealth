package com.trackmywealth.backend.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * US-07-10: where an import file names its account, for a template whose {@code
 * accountIdentificationStrategy} is {@code PREAMBLE_LINE} or {@code COLUMN}.
 *
 * <p>{@code PREAMBLE_LINE}: the first line before the header row that starts with {@code label}
 * (trimmed, ignoring case, cells joined by a space); the identifier is the rest of that line, or
 * {@code pattern}'s single capture group in it. {@code COLUMN}: {@code column} (a header name or a
 * 0-based index, like the column mapping) on every booking row; every filled cell must hold the
 * same identifier.
 */
public record ImportAccountIdentifierRule(
    @Schema(description = "IBAN or MASKED_CARD.") String kind,
    @Schema(description = "PREAMBLE_LINE: the text the identifier's line starts with.")
        String label,
    @Schema(
            description =
                "PREAMBLE_LINE, optional: an RE2 pattern with one capture group for the identifier"
                    + " in the rest of the line.")
        String pattern,
    @Schema(description = "COLUMN: the source column, by header name or 0-based index.")
        String column) {}
