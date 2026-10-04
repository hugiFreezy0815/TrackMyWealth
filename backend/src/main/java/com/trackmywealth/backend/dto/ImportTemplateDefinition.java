package com.trackmywealth.backend.dto;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Everything the CSV parser needs to read one file (US-07-03, FR-IMP-021): the parse-relevant part
 * of an import template, saved or not. Changing any of these fields on a saved template creates a
 * new template version (FR-IMP-023); its name and institution are not part of it.
 *
 * <p>Rows are read in this order: {@code preambleRowCount} physical lines are skipped, then the
 * header row is the record at {@code headerRowIndex} counted from there ({@code -1}: no header row,
 * columns are mapped by index), the records after it are data rows, and the last {@code
 * trailingSummaryRowCount} of them (e.g. a closing balance) are dropped. Empty lines are ignored
 * everywhere after the preamble.
 *
 * @param typeMapping source type text (matched trimmed and ignoring case) to a canonical {@code
 *     transaction_type}
 */
public record ImportTemplateDefinition(
    String templateClass,
    String delimiter,
    String encoding,
    String decimalSeparator,
    String thousandsSeparator,
    String dateFormat,
    int headerRowIndex,
    int preambleRowCount,
    int trailingSummaryRowCount,
    String amountRepresentation,
    String currencyMode,
    String fixedCurrency,
    ImportColumnMapping columnMapping,
    Map<String, String> typeMapping,
    String accountIdentificationStrategy) {

  public ImportTemplateDefinition {
    // Not Map.copyOf: a null value from the client must reach validation as a 422, not an NPE.
    typeMapping =
        Collections.unmodifiableMap(
            typeMapping == null ? new LinkedHashMap<>() : new LinkedHashMap<>(typeMapping));
  }

  /** Whether the file has a header row, i.e. columns may be mapped by name. */
  public boolean hasHeaderRow() {
    return headerRowIndex != ImportTemplateValues.NO_HEADER_ROW;
  }
}
