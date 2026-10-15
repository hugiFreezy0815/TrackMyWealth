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
 * @param accountIdentifier the account identifier the file names, per its template's rule
 *     (US-07-10); {@code null} for a template whose account is selected at upload
 * @param statementBalances the balances the file states, per its template's rule (US-07-12); {@code
 *     null} for a template without one
 */
public record ImportParseResult(
    List<String> headerColumns,
    String headerFingerprint,
    List<ParsedImportRow> rows,
    ImportFileAccountIdentifier accountIdentifier,
    ImportStatementBalances statementBalances) {

  /** A file of a template that does not identify its account (US-07-10). */
  public ImportParseResult(
      List<String> headerColumns, String headerFingerprint, List<ParsedImportRow> rows) {
    this(headerColumns, headerFingerprint, rows, null, null);
  }

  /** A file of a template that states no balance (US-07-12). */
  public ImportParseResult(
      List<String> headerColumns,
      String headerFingerprint,
      List<ParsedImportRow> rows,
      ImportFileAccountIdentifier accountIdentifier) {
    this(headerColumns, headerFingerprint, rows, accountIdentifier, null);
  }

  public ImportParseResult {
    headerColumns = List.copyOf(headerColumns);
    rows = List.copyOf(rows);
  }
}
