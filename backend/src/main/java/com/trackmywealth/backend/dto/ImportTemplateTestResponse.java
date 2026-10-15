package com.trackmywealth.backend.dto;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The result of a template dry run (US-07-03): the file was parsed in memory and nothing was
 * stored. The counts cover every data row; {@code rows} holds only the first {@value
 * #PREVIEW_ROWS}. Every row is counted once: {@code rowCount} is the sum of {@code parsedRowCount}
 * (rows an import would record), {@code errorRowCount} and {@code skippedRowCount} (readable rows
 * an import never records, such as a zero amount, US-07-19). {@code errorCounts} counts the
 * rejected rows per error code; skipped rows are not in it. {@code headerColumns} are what a new
 * template sends back as its own when it is saved. {@code accountIdentifierFound} says whether the
 * template's account identifier rule found exactly one valid identifier in the file (US-07-10),
 * never which; {@code null} for a template whose account is selected at upload. {@code
 * statementBalanceCount} is how many balances the template's statement balance rule read in the
 * file, {@code statementBalanceUnreadableCount} how many lines it matched but could not read
 * (US-07-12); both {@code null} for a template without that rule.
 */
public record ImportTemplateTestResponse(
    List<String> headerColumns,
    String headerFingerprint,
    int rowCount,
    int parsedRowCount,
    int errorRowCount,
    int skippedRowCount,
    Map<String, Integer> errorCounts,
    List<ImportPreviewRowResponse> rows,
    Boolean accountIdentifierFound,
    Integer statementBalanceCount,
    Integer statementBalanceUnreadableCount) {

  public ImportTemplateTestResponse {
    headerColumns = List.copyOf(headerColumns);
    errorCounts = Collections.unmodifiableMap(new LinkedHashMap<>(errorCounts));
    rows = List.copyOf(rows);
  }

  public static final int PREVIEW_ROWS = 50;
}
