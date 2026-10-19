package com.trackmywealth.backend.dto;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Everything the CSV parser needs to read one file (US-07-03, FR-IMP-021): the parse-relevant part
 * of an import template, saved or not. Changing any of these fields on a saved template creates a
 * new template version (FR-IMP-023); its name and institution are not part of it.
 *
 * <p>Rows of a CSV file are read in this order: {@code preambleRowCount} physical lines are
 * skipped, then the header row is the record at {@code headerRowIndex} counted from there ({@code
 * -1}: no header row, columns are mapped by index), the records after it are data rows, and the
 * last {@code trailingSummaryRowCount} of them (e.g. a closing balance) are dropped. Empty lines
 * are ignored everywhere after the preamble.
 *
 * <p>A PDF ({@code fileFormat} {@code PDF_TEXT} or {@code PDF_OCR}) is read through {@code
 * pdfLayout} instead: its text lines take the place of records, the layout's column names the place
 * of the header row, and {@code preambleRowCount}/{@code trailingSummaryRowCount} skip text lines.
 * Delimiter, encoding and {@code headerRowIndex} do not apply.
 *
 * @param typeMapping source type text (matched trimmed and ignoring case) to a canonical {@code
 *     transaction_type}
 * @param savingsPocketPattern US-07-22 (#325): an RE2 pattern, matched ignoring case, for the
 *     description of a move between the account and its savings pocket (Yuh's "Spareinlage"). Such
 *     a row is a {@code TRANSFER} whatever else would type it, and its commit books the other leg
 *     on the account's savings pocket. {@code null}: the bank has no pocket.
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
    String accountIdentificationStrategy,
    String fileFormat,
    ImportPdfLayout pdfLayout,
    Map<String, String> descriptionTypePatterns,
    ImportRowFilter rowFilter,
    ImportAccountIdentifierRule accountIdentifier,
    ImportStatementBalanceRule statementBalance,
    String savingsPocketPattern) {

  /** Existing saved and client templates have no savings pocket rule (US-07-22). */
  public ImportTemplateDefinition(
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
      String accountIdentificationStrategy,
      String fileFormat,
      ImportPdfLayout pdfLayout,
      Map<String, String> descriptionTypePatterns,
      ImportRowFilter rowFilter,
      ImportAccountIdentifierRule accountIdentifier,
      ImportStatementBalanceRule statementBalance) {
    this(
        templateClass,
        delimiter,
        encoding,
        decimalSeparator,
        thousandsSeparator,
        dateFormat,
        headerRowIndex,
        preambleRowCount,
        trailingSummaryRowCount,
        amountRepresentation,
        currencyMode,
        fixedCurrency,
        columnMapping,
        typeMapping,
        accountIdentificationStrategy,
        fileFormat,
        pdfLayout,
        descriptionTypePatterns,
        rowFilter,
        accountIdentifier,
        statementBalance,
        null);
  }

  /** Existing saved and client templates omit the optional statement balance rule (US-07-12). */
  public ImportTemplateDefinition(
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
      String accountIdentificationStrategy,
      String fileFormat,
      ImportPdfLayout pdfLayout,
      Map<String, String> descriptionTypePatterns,
      ImportRowFilter rowFilter,
      ImportAccountIdentifierRule accountIdentifier) {
    this(
        templateClass,
        delimiter,
        encoding,
        decimalSeparator,
        thousandsSeparator,
        dateFormat,
        headerRowIndex,
        preambleRowCount,
        trailingSummaryRowCount,
        amountRepresentation,
        currencyMode,
        fixedCurrency,
        columnMapping,
        typeMapping,
        accountIdentificationStrategy,
        fileFormat,
        pdfLayout,
        descriptionTypePatterns,
        rowFilter,
        accountIdentifier,
        null);
  }

  /** Existing templates have no regex-based type mapping. */
  public ImportTemplateDefinition(
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
      String accountIdentificationStrategy,
      String fileFormat,
      ImportPdfLayout pdfLayout) {
    this(
        templateClass,
        delimiter,
        encoding,
        decimalSeparator,
        thousandsSeparator,
        dateFormat,
        headerRowIndex,
        preambleRowCount,
        trailingSummaryRowCount,
        amountRepresentation,
        currencyMode,
        fixedCurrency,
        columnMapping,
        typeMapping,
        accountIdentificationStrategy,
        fileFormat,
        pdfLayout,
        Map.of());
  }

  /** Existing saved and client templates omit the optional row filter. */
  public ImportTemplateDefinition(
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
      String accountIdentificationStrategy,
      String fileFormat,
      ImportPdfLayout pdfLayout,
      Map<String, String> descriptionTypePatterns) {
    this(
        templateClass,
        delimiter,
        encoding,
        decimalSeparator,
        thousandsSeparator,
        dateFormat,
        headerRowIndex,
        preambleRowCount,
        trailingSummaryRowCount,
        amountRepresentation,
        currencyMode,
        fixedCurrency,
        columnMapping,
        typeMapping,
        accountIdentificationStrategy,
        fileFormat,
        pdfLayout,
        descriptionTypePatterns,
        null);
  }

  /** Existing saved and client templates omit the optional account identifier rule. */
  public ImportTemplateDefinition(
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
      String accountIdentificationStrategy,
      String fileFormat,
      ImportPdfLayout pdfLayout,
      Map<String, String> descriptionTypePatterns,
      ImportRowFilter rowFilter) {
    this(
        templateClass,
        delimiter,
        encoding,
        decimalSeparator,
        thousandsSeparator,
        dateFormat,
        headerRowIndex,
        preambleRowCount,
        trailingSummaryRowCount,
        amountRepresentation,
        currencyMode,
        fixedCurrency,
        columnMapping,
        typeMapping,
        accountIdentificationStrategy,
        fileFormat,
        pdfLayout,
        descriptionTypePatterns,
        rowFilter,
        null);
  }

  public ImportTemplateDefinition {
    // Not Map.copyOf: a null value from the client must reach validation as a 422, not an NPE.
    typeMapping =
        Collections.unmodifiableMap(
            typeMapping == null ? new LinkedHashMap<>() : new LinkedHashMap<>(typeMapping));
    descriptionTypePatterns =
        Collections.unmodifiableMap(
            descriptionTypePatterns == null
                ? new LinkedHashMap<>()
                : new LinkedHashMap<>(descriptionTypePatterns));
  }

  /**
   * Whether columns are named, i.e. may be mapped by name: a CSV file with a header row, or any PDF
   * (named by its layout).
   */
  public boolean hasHeaderRow() {
    return isPdf() || headerRowIndex != ImportTemplateValues.NO_HEADER_ROW;
  }

  /** Whether the file is a PDF, read through {@link #pdfLayout}. */
  public boolean isPdf() {
    return ImportTemplateValues.FORMAT_PDF_TEXT.equals(fileFormat)
        || ImportTemplateValues.FORMAT_PDF_OCR.equals(fileFormat);
  }

  /** Whether the file is a scanned PDF, read through local OCR. */
  public boolean isOcr() {
    return ImportTemplateValues.FORMAT_PDF_OCR.equals(fileFormat);
  }
}
