package com.trackmywealth.backend.dto;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One row of a template dry run (US-07-03): {@code canonical} for a {@code PARSED} row, {@code
 * error} for an {@code ERROR} row. See {@link ParsedImportRow} for {@code rowNumber} and {@code
 * rawData}.
 */
public record ImportPreviewRowResponse(
    int rowNumber,
    String status,
    Map<String, String> rawData,
    CanonicalImportRow canonical,
    ImportRowErrorResponse error) {

  public ImportPreviewRowResponse {
    rawData = Collections.unmodifiableMap(new LinkedHashMap<>(rawData));
  }
}
