package com.trackmywealth.backend.dto;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One data row of an import file after parsing (US-07-03, FR-IMP-012/025). A row is either {@code
 * PARSED} with its {@code canonical} values, or {@code ERROR} with a stable {@code errorCode} from
 * {@link ImportRowErrorValues} and its {@code errorArgs}; a client renders the message in its own
 * language from the two, so no English text is ever stored with the row.
 *
 * @param rowNumber 1-based position among the file's data rows (header, preamble, empty lines and
 *     summary rows not counted); in a PDF, a balance line whose balance does not add up is a row of
 *     its own, after the booking above it
 * @param rawData every cell of the row by its header text, or by its 0-based index without a header
 *     row; a repeated header text is keyed {@code "<text>#<index>"} from its second occurrence on.
 *     Kept for errors too, so a corrected template can re-parse it.
 */
public record ParsedImportRow(
    int rowNumber,
    Map<String, String> rawData,
    String status,
    String errorCode,
    Map<String, String> errorArgs,
    CanonicalImportRow canonical) {

  public ParsedImportRow {
    // Insertion order is kept: the file's column order, and the message arguments' order.
    rawData = Collections.unmodifiableMap(new LinkedHashMap<>(rawData));
    errorArgs =
        Collections.unmodifiableMap(
            errorArgs == null ? new LinkedHashMap<>() : new LinkedHashMap<>(errorArgs));
  }

  public static ParsedImportRow parsed(
      int rowNumber, Map<String, String> rawData, CanonicalImportRow canonical) {
    return new ParsedImportRow(
        rowNumber, rawData, ImportRowErrorValues.STATUS_PARSED, null, null, canonical);
  }

  public static ParsedImportRow error(
      int rowNumber, Map<String, String> rawData, String errorCode, Map<String, String> errorArgs) {
    return new ParsedImportRow(
        rowNumber, rawData, ImportRowErrorValues.STATUS_ERROR, errorCode, errorArgs, null);
  }

  public boolean isParsed() {
    return ImportRowErrorValues.STATUS_PARSED.equals(status);
  }
}
