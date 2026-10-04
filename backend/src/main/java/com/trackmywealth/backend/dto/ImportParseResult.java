package com.trackmywealth.backend.dto;

import java.util.List;

/**
 * The outcome of parsing one import file (US-07-03): its header and every data row in file order,
 * valid or not. A file-level problem (wrong template, wrong encoding, empty) is never a result: the
 * parser rejects the whole file with an {@code IMPORT_*} error code instead.
 *
 * @param headerColumns the header row's cells as written, empty without a header row
 * @param headerFingerprint FR-IMP-022's fingerprint of {@code headerColumns}, {@code null} without
 *     a header row
 */
public record ImportParseResult(
    List<String> headerColumns, String headerFingerprint, List<ParsedImportRow> rows) {

  public ImportParseResult {
    headerColumns = List.copyOf(headerColumns);
    rows = List.copyOf(rows);
  }
}
