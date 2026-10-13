package com.trackmywealth.backend.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One row of a template dry run (US-07-03): {@code canonical} for a {@code PARSED} row, {@code
 * error} for an {@code ERROR} row, both for a {@code SKIPPED} row (US-07-19). See {@link
 * ParsedImportRow} for {@code rowNumber} and {@code rawData}.
 */
public record ImportPreviewRowResponse(
    int rowNumber,
    String status,
    Map<String, String> rawData,
    CanonicalImportRow canonical,
    @Schema(
            description =
                "Why the row is not imported: for an ERROR row the error, for a SKIPPED row the"
                    + " reason it is skipped (e.g. IMPORT_ROW_ZERO_AMOUNT). Null otherwise.")
        ImportRowErrorResponse error) {

  public ImportPreviewRowResponse {
    rawData = Collections.unmodifiableMap(new LinkedHashMap<>(rawData));
  }
}
