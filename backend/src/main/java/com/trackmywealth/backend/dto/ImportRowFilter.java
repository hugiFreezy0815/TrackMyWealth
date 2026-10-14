package com.trackmywealth.backend.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Optional import template rule (US-07-09): only rows whose source column equals one of the
 * accepted values, after trimming and case folding, are parsed as bookings. Other rows remain
 * visible as SKIPPED and cannot be committed.
 *
 * <p>The column follows the existing column mapping convention: a unique header name or a 0-based
 * column index. For headerless CSV files, an index is required.
 */
public record ImportRowFilter(
    @Schema(description = "Source column name or 0-based column index, like other mappings.")
        String column,
    @Schema(description = "Accepted values, compared after trimming and ignoring case.")
        List<String> acceptedValues) {

  public ImportRowFilter {
    // Not List.copyOf: null elements must reach validateTemplate as a 422, not an NPE. A missing
    // list is an empty one, which validation rejects the same way.
    acceptedValues =
        Collections.unmodifiableList(
            acceptedValues == null ? new ArrayList<>() : new ArrayList<>(acceptedValues));
  }
}
