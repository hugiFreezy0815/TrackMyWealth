package com.trackmywealth.backend.dto;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The result of a template dry run (US-07-03): the file was parsed in memory and nothing was
 * stored. The counts cover every data row; {@code rows} holds only the first {@value
 * #PREVIEW_ROWS}. {@code errorCounts} counts the rejected rows per error code. {@code
 * headerColumns} are what a new template sends back as its own when it is saved.
 */
public record ImportTemplateTestResponse(
    List<String> headerColumns,
    String headerFingerprint,
    int rowCount,
    int parsedRowCount,
    int errorRowCount,
    Map<String, Integer> errorCounts,
    List<ImportPreviewRowResponse> rows) {

  public ImportTemplateTestResponse {
    headerColumns = List.copyOf(headerColumns);
    errorCounts = Collections.unmodifiableMap(new LinkedHashMap<>(errorCounts));
    rows = List.copyOf(rows);
  }

  public static final int PREVIEW_ROWS = 50;
}
