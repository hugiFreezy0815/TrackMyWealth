package com.trackmywealth.backend.service;

import com.trackmywealth.backend.dto.CanonicalImportRow;
import com.trackmywealth.backend.dto.ImportColumnMapping;
import com.trackmywealth.backend.dto.ImportParseResult;
import com.trackmywealth.backend.dto.ImportPdfBookingLine;
import com.trackmywealth.backend.dto.ImportPdfLayout;
import com.trackmywealth.backend.dto.ImportRowErrorValues;
import com.trackmywealth.backend.dto.ImportTemplateDefinition;
import com.trackmywealth.backend.dto.ImportTemplateRequest;
import com.trackmywealth.backend.dto.ImportTemplateValues;
import com.trackmywealth.backend.dto.ParsedImportRow;
import com.trackmywealth.backend.error.ApiErrorCode;
import com.trackmywealth.backend.error.ApiException;
import com.trackmywealth.backend.error.ImportFileRejectedException;
import com.trackmywealth.backend.error.ImportRowRejectedException;
import com.trackmywealth.backend.validation.CurrencyCodes;
import com.trackmywealth.backend.validation.Re2Patterns;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.ResolverStyle;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * US-07-03, FR-IMP-020..025: reads an import file through an import template - a declarative
 * description of one institution's export, never institution-specific code. Stateless; it never
 * touches the database, so US-07-04 parses an upload and the template dry run previews one through
 * the same {@link #parse}.
 *
 * <p>A CSV file's records are read here. A PDF statement's (#268) come from {@link
 * PdfImportReaderService}, which cuts its booking lines into the template layout's named columns.
 * From there every row goes through the same mapping, amount, date, currency and type rules.
 *
 * <p>A problem with the whole file (wrong template, wrong encoding, empty, malformed CSV) rejects
 * it with an {@code IMPORT_*} {@link ApiException} before any row is parsed, so a missing column is
 * never silently shifted (FR-IMP-023). A problem with one row makes that row an {@code ERROR} row
 * with a stable code and arguments (FR-IMP-012); every other row still parses.
 *
 * <p>Amounts are parsed into {@link BigDecimal} only, never through a {@code double}, and keep the
 * scale the file wrote them with. Uploaded content is never logged: only sizes and counts.
 */
@Service
public class ImportFileParserService {

  /** The most data rows one import file may hold (sprint 5 decision, 2026-10-02). */
  public static final int MAX_DATA_ROWS = 20_000;

  // FR-IMP-022: the header cells are joined with the ASCII unit separator, which no header holds.
  private static final String FINGERPRINT_SEPARATOR = "\u001F";
  private static final char REPLACEMENT_CHARACTER = '\uFFFD';
  private static final char BYTE_ORDER_MARK = '\uFEFF';
  // A rejected cell is echoed back to its uploader; long enough to recognise, never unbounded.
  static final int MAX_ECHOED_VALUE_LENGTH = 100;
  // transaction.amount is NUMERIC(20,4). Checked on the digits before a BigDecimal is built, whose
  // construction is quadratic in the length: a megabyte-long cell would hold a thread for minutes.
  private static final int MAX_INTEGER_DIGITS = 16;
  private static final int MAX_FRACTION_DIGITS = 4;
  private static final Pattern COLUMN_INDEX = Pattern.compile("\\d{1,4}");
  private static final Pattern DIGITS = Pattern.compile("\\d+");
  private static final Pattern MCC = Pattern.compile("\\d{4}");
  private static final LocalDate DATE_PROBE = LocalDate.of(2024, 12, 31);
  private static final Set<String> APOSTROPHES = Set.of("'", "\u2019");
  private static final Set<String> SPACES = Set.of(" ", "\u00A0", "\u202F");
  private static final String ARG_COLUMN = "column";
  private static final String ARG_VALUE = "value";
  private static final Logger LOG = LoggerFactory.getLogger(ImportFileParserService.class);

  /**
   * #268: the raw data key of a PDF row's whole booking line, with its continuation lines below it
   * - what the cells were cut from. A cell of the same name keeps its own value.
   */
  public static final String RAW_LINE_KEY = "#line";

  private final PdfImportReaderService pdfReader;

  public ImportFileParserService(PdfImportReaderService pdfReader) {
    this.pdfReader = pdfReader;
  }

  /**
   * Parses every data row of {@code content}.
   *
   * @param accountCurrency the target account's currency for currency mode {@code FROM_ACCOUNT};
   *     {@code null} leaves the rows' currency open (the template dry run has no account)
   * @throws ApiException {@code IMPORT_TEMPLATE_INVALID}/{@code _UNSUPPORTED} for a template that
   *     cannot be applied, or an {@code IMPORT_*} file-level code for a file it cannot read
   */
  public ImportParseResult parse(
      byte[] content, ImportTemplateDefinition template, String accountCurrency) {
    validateTemplate(template, null);
    List<String> header;
    List<List<String>> data;
    // #268: a PDF's booking lines, cut into the layout's named columns; null for a CSV file.
    List<ImportPdfBookingLine> bookingLines = null;
    if (template.isPdf()) {
      header = template.pdfLayout().cellNames();
      bookingLines = pdfReader.readBookingLines(content, template);
      if (bookingLines.size() > MAX_DATA_ROWS) {
        throw tooManyRows();
      }
      data = bookingLines.stream().map(ImportPdfBookingLine::cells).toList();
    } else {
      List<List<String>> records = readRecords(content, template, false);
      header = headerOf(records, template);
      data = dataRecordsOf(records, template);
      requireDecodable(template.hasHeaderRow() ? header : data.get(0));
    }
    Map<String, Integer> columns = resolveColumns(template, header);
    List<String> keys = rawDataKeys(header);
    DateTimeFormatter dateFormatter = dateFormatter(template.dateFormat());
    Map<String, String> typeMapping = normalizedTypeMapping(template.typeMapping());

    // #268: the column of each booking's running balance, when the layout checks one; and the
    // balance the bookings so far lead to, null while unknown.
    int balanceColumn =
        bookingLines == null || template.pdfLayout().balanceColumn() == null
            ? -1
            : columnIndex(template.pdfLayout().balanceColumn(), header);
    BigDecimal running = null;

    List<ParsedImportRow> rows = new ArrayList<>(data.size());
    int rejected = 0;
    for (int i = 0; i < data.size(); i++) {
      List<String> record = data.get(i);
      Map<String, String> rawData = rawData(record, keys, template.hasHeaderRow());
      ImportPdfBookingLine booking = bookingLines == null ? null : bookingLines.get(i);
      if (booking != null) {
        rawData.putIfAbsent(RAW_LINE_KEY, booking.fullText());
        running = balanceBefore(booking, running, template);
      }
      try {
        if (booking != null && !booking.matched()) {
          throw rowError(ImportRowErrorValues.LINE_UNMATCHED, ARG_VALUE, booking.line());
        }
        CanonicalImportRow canonical =
            canonicalRow(record, template, columns, dateFormatter, typeMapping, accountCurrency);
        if (balanceColumn >= 0) {
          BigDecimal stated = statedBalance(record, balanceColumn, header, template);
          BigDecimal expected = running == null ? null : running.add(canonical.amount());
          // A wrong row does not make every row after it wrong: the next one starts from the
          // balance this one states.
          running = stated == null ? expected : stated;
          if (stated != null && expected != null && stated.compareTo(expected) != 0) {
            throw rowError(
                ImportRowErrorValues.BALANCE_MISMATCH,
                ARG_VALUE,
                stated.toPlainString(),
                "expected",
                expected.toPlainString());
          }
        }
        rows.add(ParsedImportRow.parsed(rows.size() + 1, rawData, canonical));
      } catch (ImportRowRejectedException e) {
        rejected++;
        rows.add(ParsedImportRow.error(rows.size() + 1, rawData, e.getCode(), e.getArgs()));
        if (balanceColumn >= 0 && !ImportRowErrorValues.BALANCE_MISMATCH.equals(e.getCode())) {
          // Its amount is unknown: only the balance it states, if any, carries on.
          running = quietBalance(record, balanceColumn, header, template);
        }
      }
      if (balanceColumn >= 0 && booking != null) {
        for (ImportPdfBookingLine.BalanceLine below : booking.balancesAfter()) {
          Optional<ParsedImportRow> mismatch =
              balanceLineMismatch(below, running, rows.size() + 1, template);
          if (mismatch.isPresent()) {
            rejected++;
            rows.add(mismatch.get());
          }
        }
      }
    }
    if (LOG.isDebugEnabled()) {
      LOG.debug(
          "Parsed import file: {} bytes, {} data rows, {} rejected",
          content.length,
          rows.size(),
          rejected);
    }
    return new ImportParseResult(header, fileFingerprint(template, header, bookingLines), rows);
  }

  // The fingerprint of the header the file holds: a CSV file's header row; for a PDF, its layout's
  // header labels, but only when the statement holds their header line (PR #281 review) - the
  // labels come from the template, so without that line they say nothing about the file.
  private static String fileFingerprint(
      ImportTemplateDefinition template,
      List<String> header,
      List<ImportPdfBookingLine> bookingLines) {
    if (bookingLines != null && bookingLines.stream().noneMatch(ImportPdfBookingLine::headed)) {
      return null;
    }
    return headerFingerprint(template, header);
  }

  // PR #281 review: a balance line below a booking, before the next one of its section (e.g. the
  // section's closing balance), must state the balance the bookings lead to. Otherwise a booking
  // between them was lost - say, a line the balance line pattern took for its own. The balance line
  // is then an error row of its own, so the bookings around it, read correctly, still import.
  // Empty when it adds up, or when nothing is known to check it against.
  private static Optional<ParsedImportRow> balanceLineMismatch(
      ImportPdfBookingLine.BalanceLine below,
      BigDecimal running,
      int rowNumber,
      ImportTemplateDefinition template) {
    BigDecimal stated = running == null ? null : quietAmount(below.balance(), template);
    if (stated == null || stated.compareTo(running) == 0) {
      return Optional.empty();
    }
    return Optional.of(
        ParsedImportRow.error(
            rowNumber,
            Map.of(RAW_LINE_KEY, below.line()),
            ImportRowErrorValues.BALANCE_LINE_MISMATCH,
            rowArgs(ARG_VALUE, stated.toPlainString(), "expected", running.toPlainString())));
  }

  // The balance before booking: none at a section's start, else the one a balance line stated
  // since the booking before, else what the bookings so far lead to.
  private static BigDecimal balanceBefore(
      ImportPdfBookingLine booking, BigDecimal running, ImportTemplateDefinition template) {
    if (booking.statedBalance() == null) {
      return booking.sectionStart() ? null : running;
    }
    return quietAmount(booking.statedBalance(), template);
  }

  // A balance line's balance; null when it is no amount, which then checks nothing.
  private static BigDecimal quietAmount(String balance, ImportTemplateDefinition template) {
    try {
      return parseAmount(balance, "balanceLinePattern", template, false);
    } catch (ImportRowRejectedException e) {
      return null;
    }
  }

  // The running balance the booking states in its balance column; null where it states none.
  private static BigDecimal statedBalance(
      List<String> record, int column, List<String> header, ImportTemplateDefinition template) {
    String cell = optionalCell(record, column);
    return cell == null ? null : parseAmount(cell, header.get(column), template, false);
  }

  private static BigDecimal quietBalance(
      List<String> record, int column, List<String> header, ImportTemplateDefinition template) {
    try {
      return statedBalance(record, column, header, template);
    } catch (ImportRowRejectedException e) {
      return null;
    }
  }

  /**
   * The header cells of {@code content} read with {@code template}'s encoding, delimiter and
   * skipped rows - for template detection, which tries every candidate's reading of the file. Empty
   * for a template without a header row. A PDF has no header row to read: detection tests a PDF
   * template through {@link PdfImportReaderService#isLayoutOf}, and a PDF template here is a 422.
   *
   * @throws ApiException an {@code IMPORT_*} file-level code when the file cannot be read this way
   */
  public List<String> readHeader(byte[] content, ImportTemplateDefinition template) {
    if (template.isPdf()) {
      throw unsupported("A PDF template has no header row to read.");
    }
    List<String> header = headerOf(readRecords(content, template, true), template);
    requireDecodable(header);
    return header;
  }

  /**
   * The mapped source columns {@code header} lacks, in mapping order: never present, or present
   * more than once (ambiguous by name). Empty when the template can read a file with this header.
   */
  public List<String> missingColumns(ImportColumnMapping mapping, List<String> header) {
    List<String> missing = new ArrayList<>();
    for (String column : mapping.mappedColumns().values()) {
      if (columnIndex(column, header) < 0 && !missing.contains(column)) {
        missing.add(column);
      }
    }
    return missing;
  }

  /**
   * Whether the template's header row identifies its files (FR-IMP-022): a CSV file's, or a PDF
   * statement's booking table header (its layout's header labels, #268). A PDF layout's other
   * columns are named by the template, not read from the file, so they identify nothing.
   */
  public static boolean hasFingerprint(ImportTemplateDefinition template) {
    if (template.isPdf()) {
      return template.pdfLayout() != null && template.pdfLayout().hasHeaderLabels();
    }
    return template.hasHeaderRow();
  }

  /**
   * The template's header fingerprint, per {@link #hasFingerprint}: of {@code headerColumns} for a
   * CSV template, of the header labels for a PDF one; {@code null} for none.
   */
  public static String headerFingerprint(
      ImportTemplateDefinition template, List<String> headerColumns) {
    if (!hasFingerprint(template)) {
      return null;
    }
    return fingerprint(template.isPdf() ? template.pdfLayout().headerLabels() : headerColumns);
  }

  /**
   * FR-IMP-022: SHA-256 (hex) of the header cells, each trimmed and lower-cased, joined by the
   * ASCII unit separator - the same file format yields the same fingerprint whatever its data rows.
   */
  public static String fingerprint(List<String> headerColumns) {
    List<String> normalized =
        headerColumns.stream().map(ImportFileParserService::normalize).toList();
    try {
      byte[] hash =
          MessageDigest.getInstance("SHA-256")
              .digest(
                  String.join(FINGERPRINT_SEPARATOR, normalized).getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(hash);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("Every Java runtime provides SHA-256.", e);
    }
  }

  /**
   * Checks that {@code template} can be applied at all, before any file is read.
   *
   * @param headerColumns the sample's header the template was built from, to check every mapping by
   *     name against; {@code null} skips that check (the file's own header is checked when parsed)
   * @throws ApiException 422 {@code IMPORT_TEMPLATE_UNSUPPORTED} for a template class or account
   *     identification this release does not import, 422 {@code IMPORT_TEMPLATE_INVALID} naming the
   *     {@code field} for any other rule
   */
  public void validateTemplate(ImportTemplateDefinition template, List<String> headerColumns) {
    if (!ImportTemplateValues.CLASS_CASH_TRANSACTIONS.equals(template.templateClass())) {
      throw unsupported("Only cash transaction templates can be imported so far.");
    }
    if (!ImportTemplateValues.ACCOUNT_USER_SELECTED.equals(
        template.accountIdentificationStrategy())) {
      throw unsupported("The account is selected at upload; other strategies are not supported.");
    }
    if (!ImportTemplateValues.FILE_FORMATS.contains(template.fileFormat())) {
      throw invalid(
          "fileFormat",
          "The file format must be one of " + ImportTemplateValues.FILE_FORMATS + ".");
    }
    if (template.isPdf()) {
      validatePdfLayout(template);
    } else if (template.pdfLayout() != null) {
      throw invalid("pdfLayout", "Only a PDF template has a PDF layout.");
    }
    if (!ImportTemplateValues.ENCODINGS.contains(template.encoding())) {
      throw invalid(
          "encoding", "The encoding must be one of " + ImportTemplateValues.ENCODINGS + ".");
    }
    if (!ImportTemplateValues.AMOUNT_REPRESENTATIONS.contains(template.amountRepresentation())) {
      throw invalid("amountRepresentation", "Unknown amount representation.");
    }
    if (!ImportTemplateValues.CURRENCY_MODES.contains(template.currencyMode())) {
      throw invalid("currencyMode", "Unknown currency mode.");
    }
    validateRowCounts(template);
    validateSeparators(template);
    if (!isValidDateFormat(template.dateFormat())) {
      throw invalid("dateFormat", "The date format is not a valid date pattern.");
    }
    validateCurrency(template);
    // A PDF's columns are its layout's: every mapping by name is checked against them.
    validateColumnMapping(
        template, template.isPdf() ? template.pdfLayout().cellNames() : headerColumns);
    validateTypeMapping(template.typeMapping());
  }

  // --- template rules ----------------------------------------------------------------------

  // #268: the layout must name each capture group of a row pattern that RE2 can compile, and its
  // optional fields must name columns it has.
  private static void validatePdfLayout(ImportTemplateDefinition template) {
    ImportPdfLayout layout = template.pdfLayout();
    if (layout == null) {
      throw invalid("pdfLayout", "A PDF template needs a PDF layout.");
    }
    List<String> columns = layout.columns();
    if (columns.isEmpty() || columns.size() > ImportPdfLayout.MAX_COLUMNS) {
      throw invalid("pdfLayout.columns", "Name 1 to " + ImportPdfLayout.MAX_COLUMNS + " columns.");
    }
    Set<String> seen = new HashSet<>();
    for (String column : columns) {
      requireCellName(column, seen, "pdfLayout.columns");
    }
    String marker = layout.documentMarker();
    if (marker == null || marker.isBlank() || marker.length() > ImportPdfLayout.MAX_MARKER_LENGTH) {
      throw invalid(
          "pdfLayout.documentMarker",
          "The document marker is text every statement of this layout contains.");
    }
    patternGroups(layout.recordStartPattern(), "pdfLayout.recordStartPattern");
    if (layout.recordStartPattern().isBlank()) {
      throw invalid("pdfLayout.recordStartPattern", "The record start pattern must not be blank.");
    }
    if (patternGroups(layout.rowPattern(), "pdfLayout.rowPattern") != columns.size()) {
      throw invalid(
          "pdfLayout.rowPattern", "The row pattern needs exactly one capture group per column.");
    }
    validateHeaderLabels(template, seen);
    validateSections(layout, seen);
    if (layout.balanceLinePattern() != null) {
      requireNonBlankPattern(layout.balanceLinePattern(), "pdfLayout.balanceLinePattern");
    }
    requireCell(layout, layout.continuationColumn(), "pdfLayout.continuationColumn");
    requireCell(layout, layout.balanceColumn(), "pdfLayout.balanceColumn");
  }

  private static void validateHeaderLabels(ImportTemplateDefinition template, Set<String> seen) {
    List<String> labels = template.pdfLayout().headerLabels();
    if (labels.size() > ImportPdfLayout.MAX_COLUMNS) {
      throw invalid(
          "pdfLayout.headerLabels", "Name at most " + ImportPdfLayout.MAX_COLUMNS + " labels.");
    }
    if (!labels.isEmpty() && template.isOcr()) {
      throw invalid(
          "pdfLayout.headerLabels",
          "Header labels locate columns by their place on the page, which only a text layer"
              + " (PDF_TEXT) gives.");
    }
    for (String label : labels) {
      requireCellName(label, seen, "pdfLayout.headerLabels");
    }
  }

  private static void validateSections(ImportPdfLayout layout, Set<String> seen) {
    int groups =
        layout.sectionPattern() == null
            ? 0
            : requireNonBlankPattern(layout.sectionPattern(), "pdfLayout.sectionPattern");
    if (layout.sectionColumn() != null) {
      if (groups == 0) {
        throw invalid(
            "pdfLayout.sectionColumn",
            "A section column holds the first capture group of the section pattern, which needs"
                + " one.");
      }
      requireCellName(layout.sectionColumn(), seen, "pdfLayout.sectionColumn");
    }
  }

  // A cell's name: not blank, not too long, and no other cell's (compared as the mapping does).
  private static void requireCellName(String name, Set<String> seen, String field) {
    if (name == null || name.isBlank() || !seen.add(normalize(name))) {
      throw invalid(field, "Each column needs a name of its own.");
    }
    if (name.length() > ImportPdfLayout.MAX_COLUMN_NAME_LENGTH) {
      throw invalid(
          field,
          "A column name has at most " + ImportPdfLayout.MAX_COLUMN_NAME_LENGTH + " characters.");
    }
  }

  // An optional field naming a column: when set, one of the layout's cells.
  private static void requireCell(ImportPdfLayout layout, String name, String field) {
    if (name != null && matchesByName(name, layout.cellNames()).size() != 1) {
      throw invalid(field, "Name one of the layout's columns or header labels.");
    }
  }

  private static int requireNonBlankPattern(String pattern, String field) {
    if (pattern != null && pattern.isBlank()) {
      throw invalid(field, "The pattern must not be blank.");
    }
    return patternGroups(pattern, field);
  }

  // The capture groups of an RE2 pattern; a missing, too long, invalid or too large one is a 422.
  private static int patternGroups(String pattern, String field) {
    if (pattern == null || pattern.length() > ImportPdfLayout.MAX_PATTERN_LENGTH) {
      throw invalid(
          field,
          "A pattern is required, with at most "
              + ImportPdfLayout.MAX_PATTERN_LENGTH
              + " characters.");
    }
    try {
      return Re2Patterns.compile(pattern).groupCount();
    } catch (IllegalArgumentException e) {
      ApiException exception =
          new ApiException(
              HttpStatus.UNPROCESSABLE_CONTENT,
              ApiErrorCode.IMPORT_TEMPLATE_INVALID,
              e.getMessage(),
              e);
      exception.getBody().setProperty("field", field);
      throw exception;
    }
  }

  // Bean Validation checks these on a saved template, but not on the unsaved dry run's (which
  // needs no name); out of range they would index past the file's records.
  private static void validateRowCounts(ImportTemplateDefinition template) {
    int max = ImportTemplateRequest.MAX_SKIPPED_ROWS;
    if (template.headerRowIndex() < ImportTemplateValues.NO_HEADER_ROW
        || template.headerRowIndex() > max) {
      throw invalid(
          "headerRowIndex", "The header row index must be -1 (no header row) or 0 to " + max + ".");
    }
    if (template.preambleRowCount() < 0 || template.preambleRowCount() > max) {
      throw invalid("preambleRowCount", "The preamble row count must be 0 to " + max + ".");
    }
    if (template.trailingSummaryRowCount() < 0 || template.trailingSummaryRowCount() > max) {
      throw invalid(
          "trailingSummaryRowCount", "The trailing summary row count must be 0 to " + max + ".");
    }
  }

  private static void validateSeparators(ImportTemplateDefinition template) {
    String delimiter = template.delimiter();
    if (!isSingleCharacter(delimiter)
        || "\"".equals(delimiter)
        || "\r".equals(delimiter)
        || "\n".equals(delimiter)) {
      throw invalid("delimiter", "The delimiter must be one character other than a quote.");
    }
    String decimal = template.decimalSeparator();
    if (!isSingleCharacter(decimal) || DIGITS.matcher(decimal).matches()) {
      throw invalid("decimalSeparator", "The decimal separator must be one non-digit character.");
    }
    String thousands = template.thousandsSeparator();
    if (thousands != null
        && (!isSingleCharacter(thousands)
            || DIGITS.matcher(thousands).matches()
            || thousands.equals(decimal))) {
      throw invalid(
          "thousandsSeparator",
          "The thousands separator must be one non-digit character other than the decimal"
              + " separator.");
    }
  }

  private static void validateCurrency(ImportTemplateDefinition template) {
    boolean fixed = ImportTemplateValues.CURRENCY_FIXED.equals(template.currencyMode());
    if (fixed && !CurrencyCodes.isValid(template.fixedCurrency())) {
      throw invalid("fixedCurrency", "Currency mode FIXED needs an ISO 4217 fixed currency.");
    }
    if (!fixed && template.fixedCurrency() != null) {
      throw invalid("fixedCurrency", "A fixed currency is only used with currency mode FIXED.");
    }
  }

  private static void validateColumnMapping(
      ImportTemplateDefinition template, List<String> headerColumns) {
    ImportColumnMapping mapping = template.columnMapping();
    if (mapping == null) {
      throw invalid("columnMapping", "A column mapping is required.");
    }
    requireMapped(mapping.bookingDate(), "bookingDate");
    if (ImportTemplateValues.AMOUNT_SEPARATE_DEBIT_CREDIT.equals(template.amountRepresentation())) {
      requireMapped(mapping.debitAmount(), "debitAmount");
      requireMapped(mapping.creditAmount(), "creditAmount");
    } else {
      requireMapped(mapping.amount(), "amount");
    }
    if (ImportTemplateValues.CURRENCY_PER_ROW.equals(template.currencyMode())) {
      requireMapped(mapping.currency(), "currency");
    }

    for (Map.Entry<String, String> entry : mapping.mappedColumns().entrySet()) {
      String field = "columnMapping." + entry.getKey();
      String column = entry.getValue();
      if (!template.hasHeaderRow()) {
        if (!COLUMN_INDEX.matcher(column).matches()) {
          throw invalid(field, "Without a header row, columns are mapped by 0-based index.");
        }
      } else if (headerColumns != null && columnIndex(column, headerColumns) < 0) {
        throw invalid(
            field,
            matchesByName(column, headerColumns).size() > 1
                ? "The header names this column more than once; map it by its 0-based index."
                : "The header has no such column.");
      }
    }
  }

  private static void requireMapped(String column, String field) {
    if (column == null || column.isBlank()) {
      throw invalid("columnMapping." + field, "This template must map " + field + ".");
    }
  }

  private static void validateTypeMapping(Map<String, String> typeMapping) {
    Map<String, String> seen = new HashMap<>();
    for (Map.Entry<String, String> entry : typeMapping.entrySet()) {
      String source = entry.getKey() == null ? "" : normalize(entry.getKey());
      String type = entry.getValue();
      if (source.isEmpty()) {
        throw invalid("typeMapping", "A source type must not be blank.");
      }
      if (type == null || !ImportTemplateValues.CASH_TRANSACTION_TYPES.contains(type)) {
        throw invalid(
            "typeMapping",
            "A cash transactions template maps only to "
                + ImportTemplateValues.CASH_TRANSACTION_TYPES.stream().sorted().toList()
                + ".");
      }
      String previous = seen.put(source, type);
      if (previous != null && !previous.equals(type)) {
        throw invalid(
            "typeMapping", "Two source types differing only in case map to different types.");
      }
    }
  }

  private static boolean isValidDateFormat(String pattern) {
    if (pattern == null || pattern.isBlank()) {
      return false;
    }
    try {
      DateTimeFormatter formatter = dateFormatter(pattern);
      return DATE_PROBE.equals(LocalDate.parse(DATE_PROBE.format(formatter), formatter));
    } catch (IllegalArgumentException | DateTimeException e) {
      return false;
    }
  }

  // --- reading the file --------------------------------------------------------------------

  // Every non-blank record after the preamble, header included; with headerOnly, only up to the
  // header row (detection reads the file once per template and needs nothing below the header).
  private static List<List<String>> readRecords(
      byte[] content, ImportTemplateDefinition template, boolean headerOnly) {
    String text = skipLines(decode(content, template.encoding()), template.preambleRowCount());
    CSVFormat format =
        CSVFormat.RFC4180
            .builder()
            .setDelimiter(template.delimiter().charAt(0))
            .setIgnoreEmptyLines(true)
            .get();
    List<List<String>> records = new ArrayList<>();
    int maxRecords =
        (template.hasHeaderRow() ? template.headerRowIndex() + 1 : 0)
            + MAX_DATA_ROWS
            + template.trailingSummaryRowCount();
    try (CSVParser parser = CSVParser.parse(text, format)) {
      for (CSVRecord record : parser) {
        if (!isBlank(record)) {
          records.add(record.toList());
          if (headerOnly && records.size() > template.headerRowIndex()) {
            break;
          }
          if (records.size() > maxRecords) {
            throw tooManyRows();
          }
        }
      }
    } catch (IOException | UncheckedIOException e) {
      throw new ImportFileRejectedException(
          ApiErrorCode.IMPORT_FILE_MALFORMED,
          "The file is not valid CSV with this template's delimiter (e.g. an unclosed quote).",
          e);
    }
    if (records.isEmpty()) {
      throw fileError(ApiErrorCode.IMPORT_FILE_EMPTY, "The file holds no rows to import.");
    }
    return records;
  }

  private static String decode(byte[] content, String encoding) {
    try {
      String text =
          Charset.forName(encoding)
              .newDecoder()
              .onMalformedInput(CodingErrorAction.REPLACE)
              .onUnmappableCharacter(CodingErrorAction.REPLACE)
              .decode(ByteBuffer.wrap(content))
              .toString();
      return !text.isEmpty() && text.charAt(0) == BYTE_ORDER_MARK ? text.substring(1) : text;
    } catch (CharacterCodingException e) {
      throw new IllegalStateException("A replacing decoder never rejects input.", e);
    }
  }

  // The preamble is skipped as physical lines (CR, LF or CRLF), blank ones included: an
  // institution's account summary above the header is rarely valid CSV with the data's delimiter.
  private static String skipLines(String text, int count) {
    int position = 0;
    for (int line = 0; line < count; line++) {
      int end = position;
      while (end < text.length() && text.charAt(end) != '\n' && text.charAt(end) != '\r') {
        end++;
      }
      if (end == text.length()) {
        throw fileError(ApiErrorCode.IMPORT_FILE_EMPTY, "The file ends within its preamble.");
      }
      position =
          text.charAt(end) == '\r' && end + 1 < text.length() && text.charAt(end + 1) == '\n'
              ? end + 2
              : end + 1;
    }
    return text.substring(position);
  }

  private static List<String> headerOf(
      List<List<String>> records, ImportTemplateDefinition template) {
    if (!template.hasHeaderRow()) {
      return List.of();
    }
    if (records.size() <= template.headerRowIndex()) {
      throw fileError(ApiErrorCode.IMPORT_FILE_EMPTY, "The file has no header row.");
    }
    return records.get(template.headerRowIndex());
  }

  private static List<List<String>> dataRecordsOf(
      List<List<String>> records, ImportTemplateDefinition template) {
    int first = template.hasHeaderRow() ? template.headerRowIndex() + 1 : 0;
    int end = records.size() - template.trailingSummaryRowCount();
    if (end <= first) {
      throw fileError(ApiErrorCode.IMPORT_FILE_NO_DATA_ROWS, "The file has no data rows.");
    }
    return records.subList(first, end);
  }

  // A file decoded with the wrong charset shows replacement characters; the header is where a
  // wrong guess shows first, and judging it never depends on the data rows' content.
  private static void requireDecodable(List<String> cells) {
    for (String cell : cells) {
      if (cell.indexOf(REPLACEMENT_CHARACTER) >= 0) {
        throw fileError(
            ApiErrorCode.IMPORT_ENCODING_SUSPECT,
            "The file does not look like it is encoded in this template's encoding.");
      }
    }
  }

  private static Map<String, Integer> resolveColumns(
      ImportTemplateDefinition template, List<String> header) {
    Map<String, Integer> columns = new HashMap<>();
    List<String> missing = new ArrayList<>();
    for (Map.Entry<String, String> entry : template.columnMapping().mappedColumns().entrySet()) {
      String column = entry.getValue();
      int index =
          template.hasHeaderRow() ? columnIndex(column, header) : Integer.parseInt(column.strip());
      if (index < 0) {
        if (!missing.contains(column)) {
          missing.add(column);
        }
      } else {
        columns.put(entry.getKey(), index);
      }
    }
    if (!missing.isEmpty()) {
      throw fileError(
              ApiErrorCode.IMPORT_TEMPLATE_MISMATCH,
              "The file's header lacks columns this template maps: " + missing.size() + ".")
          .withProperty("missingColumns", missing);
    }
    return columns;
  }

  // A column is named by header text (trimmed, ignoring case; it must occur exactly once) or by
  // its 0-based index. -1 when the header has no such single column.
  private static int columnIndex(String column, List<String> header) {
    List<Integer> matches = matchesByName(column, header);
    if (!matches.isEmpty()) {
      // A name occurring more than once is ambiguous: such a column is mapped by index.
      return matches.size() == 1 ? matches.get(0) : -1;
    }
    if (COLUMN_INDEX.matcher(column.strip()).matches()) {
      int index = Integer.parseInt(column.strip());
      return index < header.size() ? index : -1;
    }
    return -1;
  }

  private static List<Integer> matchesByName(String column, List<String> header) {
    String wanted = normalize(column);
    List<Integer> matches = new ArrayList<>();
    for (int i = 0; i < header.size(); i++) {
      if (normalize(header.get(i)).equals(wanted)) {
        matches.add(i);
      }
    }
    return matches;
  }

  private static List<String> rawDataKeys(List<String> header) {
    List<String> keys = new ArrayList<>(header.size());
    Set<String> seen = new HashSet<>();
    for (int i = 0; i < header.size(); i++) {
      String text = header.get(i).strip();
      keys.add(text.isEmpty() || !seen.add(text) ? text + "#" + i : text);
    }
    return keys;
  }

  // Every cell of the row as written, keyed per ParsedImportRow#rawData.
  private static Map<String, String> rawData(
      List<String> record, List<String> keys, boolean hasHeaderRow) {
    Map<String, String> raw = new LinkedHashMap<>();
    for (int i = 0; i < record.size(); i++) {
      String key;
      if (!hasHeaderRow) {
        key = String.valueOf(i);
      } else if (i < keys.size()) {
        key = keys.get(i);
      } else {
        key = "#" + i;
      }
      raw.put(key, record.get(i));
    }
    return raw;
  }

  // --- one row ---------------------------------------------------------------------------

  private static CanonicalImportRow canonicalRow(
      List<String> record,
      ImportTemplateDefinition template,
      Map<String, Integer> columns,
      DateTimeFormatter dateFormatter,
      Map<String, String> typeMapping,
      String accountCurrency) {
    ImportColumnMapping mapping = template.columnMapping();
    LocalDate bookingDate =
        parseDate(
            requiredCell(record, columns.get("bookingDate"), mapping.bookingDate()),
            mapping.bookingDate(),
            template.dateFormat(),
            dateFormatter);
    String valueDateCell = optionalCell(record, columns.get("valueDate"));
    LocalDate valueDate =
        valueDateCell == null
            ? null
            : parseDate(valueDateCell, mapping.valueDate(), template.dateFormat(), dateFormatter);
    BigDecimal amount = amountOf(record, template, columns);
    String currency = currencyOf(record, template, columns, accountCurrency);
    String type =
        transactionTypeOf(
            optionalCell(record, columns.get("transactionType")),
            mapping.transactionType(),
            typeMapping,
            amount);
    String mcc = optionalCell(record, columns.get("mcc"));
    if (mcc != null && !MCC.matcher(mcc).matches()) {
      throw rowError(ImportRowErrorValues.MCC_INVALID, ARG_COLUMN, mapping.mcc(), ARG_VALUE, mcc);
    }
    return new CanonicalImportRow(
        bookingDate,
        valueDate,
        amount,
        currency,
        type,
        optionalCell(record, columns.get("description")),
        optionalCell(record, columns.get("counterpartyName")),
        optionalCell(record, columns.get("externalId")),
        mcc,
        optionalCell(record, columns.get("iso20022BankTransactionCode")),
        optionalCell(record, columns.get("notes")));
  }

  // The trimmed cell, or null when the column is unmapped, beyond this (short) row, or empty.
  private static String optionalCell(List<String> record, Integer index) {
    if (index == null || index >= record.size()) {
      return null;
    }
    String value = record.get(index).strip();
    return value.isEmpty() ? null : value;
  }

  private static String requiredCell(List<String> record, int index, String column) {
    if (index >= record.size()) {
      throw rowError(ImportRowErrorValues.COLUMN_MISSING, ARG_COLUMN, column);
    }
    String value = record.get(index).strip();
    if (value.isEmpty()) {
      throw rowError(ImportRowErrorValues.VALUE_MISSING, ARG_COLUMN, column);
    }
    return value;
  }

  private static LocalDate parseDate(
      String value, String column, String pattern, DateTimeFormatter formatter) {
    try {
      return LocalDate.parse(value, formatter);
    } catch (DateTimeException e) {
      throw new ImportRowRejectedException(
          ImportRowErrorValues.DATE_UNPARSEABLE,
          rowArgs(ARG_COLUMN, column, ARG_VALUE, value, "pattern", pattern),
          e);
    }
  }

  private static BigDecimal amountOf(
      List<String> record, ImportTemplateDefinition template, Map<String, Integer> columns) {
    ImportColumnMapping mapping = template.columnMapping();
    String representation = template.amountRepresentation();
    if (!ImportTemplateValues.AMOUNT_SEPARATE_DEBIT_CREDIT.equals(representation)) {
      String value = requiredCell(record, columns.get("amount"), mapping.amount());
      return parseAmount(
          value,
          mapping.amount(),
          template,
          ImportTemplateValues.AMOUNT_NEGATIVE_IN_PARENTHESES.equals(representation));
    }
    // The column decides the sign, whichever way a bank writes it there. A side holding zero
    // counts as empty: some exports fill the unused side with 0,00.
    String debitCell = optionalCell(record, columns.get("debitAmount"));
    String creditCell = optionalCell(record, columns.get("creditAmount"));
    BigDecimal debit =
        debitCell == null ? null : parseAmount(debitCell, mapping.debitAmount(), template, false);
    BigDecimal credit =
        creditCell == null
            ? null
            : parseAmount(creditCell, mapping.creditAmount(), template, false);
    boolean hasDebit = debit != null && debit.signum() != 0;
    boolean hasCredit = credit != null && credit.signum() != 0;
    if (hasDebit && hasCredit) {
      throw rowError(
          ImportRowErrorValues.AMOUNT_BOTH_SIDES,
          "debitColumn",
          mapping.debitAmount(),
          "creditColumn",
          mapping.creditAmount());
    }
    if (hasDebit) {
      return debit.abs().negate();
    }
    if (hasCredit) {
      return credit.abs();
    }
    // Neither side holds an amount, zeros included: no booking to import.
    throw rowError(
        ImportRowErrorValues.VALUE_MISSING,
        ARG_COLUMN,
        mapping.debitAmount() + " / " + mapping.creditAmount());
  }

  /**
   * {@code value} as a decimal with the template's separators: an optional sign (or, for {@code
   * NEGATIVE_IN_PARENTHESES}, parentheses), digits grouped by the thousands separator in groups of
   * three, and an optional fraction. The Swiss apostrophe is accepted in both its forms ({@code '}
   * and {@code \u2019}), a space separator as any space, including the non-breaking ones. The value
   * must fit {@code NUMERIC(20,4)}: zeros beyond four decimal places are dropped, anything else
   * outside it is {@code IMPORT_ROW_AMOUNT_OUT_OF_RANGE}.
   */
  static BigDecimal parseAmount(
      String value, String column, ImportTemplateDefinition template, boolean parentheses) {
    String text = value.strip();
    boolean negative = false;
    if (parentheses && text.length() > 2 && text.startsWith("(") && text.endsWith(")")) {
      negative = true;
      text = text.substring(1, text.length() - 1).strip();
    } else if (!text.isEmpty() && "+-\u2212".indexOf(text.charAt(0)) >= 0) {
      negative = text.charAt(0) != '+';
      text = text.substring(1).strip();
    }
    String decimal = template.decimalSeparator();
    int decimalAt = text.indexOf(decimal);
    String integerPart = decimalAt < 0 ? text : text.substring(0, decimalAt);
    String fraction = decimalAt < 0 ? null : text.substring(decimalAt + decimal.length());
    String digits = groupedDigits(integerPart, template.thousandsSeparator());
    boolean valid =
        digits != null
            && (fraction == null ? !digits.isEmpty() : DIGITS.matcher(fraction).matches());
    if (!valid) {
      throw rowError(
          ImportRowErrorValues.AMOUNT_UNPARSEABLE, ARG_COLUMN, column, ARG_VALUE, value.strip());
    }
    String integerDigits = stripLeadingZeros(digits);
    String fractionDigits = fraction == null ? "" : fraction;
    if (fractionDigits.length() > MAX_FRACTION_DIGITS) {
      // Zeros beyond the stored scale lose nothing ("1.500000" is 1.5000).
      fractionDigits = stripTrailingZeros(fractionDigits, MAX_FRACTION_DIGITS);
    }
    if (integerDigits.length() > MAX_INTEGER_DIGITS
        || fractionDigits.length() > MAX_FRACTION_DIGITS) {
      throw rowError(
          ImportRowErrorValues.AMOUNT_OUT_OF_RANGE, ARG_COLUMN, column, ARG_VALUE, value.strip());
    }
    BigDecimal amount =
        new BigDecimal(
            (integerDigits.isEmpty() ? "0" : integerDigits)
                + (fraction == null ? "" : "." + fractionDigits));
    return negative ? amount.negate() : amount;
  }

  // The integer part's digits without separators; null unless it is plain digits or digits
  // grouped by threes (a first group of one to three). Empty stays empty (".50" is 0.50).
  private static String groupedDigits(String integerPart, String thousandsSeparator) {
    if (integerPart.isEmpty()) {
      return integerPart;
    }
    Set<String> separators;
    if (thousandsSeparator == null) {
      separators = Set.of();
    } else if (APOSTROPHES.contains(thousandsSeparator)) {
      separators = APOSTROPHES;
    } else if (SPACES.contains(thousandsSeparator)) {
      separators = SPACES;
    } else {
      separators = Set.of(thousandsSeparator);
    }
    List<String> groups = new ArrayList<>();
    StringBuilder group = new StringBuilder();
    for (char character : integerPart.toCharArray()) {
      if (separators.contains(String.valueOf(character))) {
        groups.add(group.toString());
        group.setLength(0);
      } else {
        group.append(character);
      }
    }
    groups.add(group.toString());
    boolean grouped = groups.size() > 1;
    if (!grouped) {
      return DIGITS.matcher(integerPart).matches() ? integerPart : null;
    }
    for (int i = 0; i < groups.size(); i++) {
      String part = groups.get(i);
      boolean wellFormed =
          DIGITS.matcher(part).matches() && (i == 0 ? part.length() <= 3 : part.length() == 3);
      if (!wellFormed) {
        return null;
      }
    }
    return String.join("", groups);
  }

  private static String currencyOf(
      List<String> record,
      ImportTemplateDefinition template,
      Map<String, Integer> columns,
      String accountCurrency) {
    return switch (template.currencyMode()) {
      case ImportTemplateValues.CURRENCY_FIXED -> template.fixedCurrency();
      case ImportTemplateValues.CURRENCY_PER_ROW -> {
        String column = template.columnMapping().currency();
        String value = requiredCell(record, columns.get("currency"), column);
        String code = value.toUpperCase(Locale.ROOT);
        if (!CurrencyCodes.isValid(code)) {
          throw rowError(
              ImportRowErrorValues.CURRENCY_INVALID, ARG_COLUMN, column, ARG_VALUE, value);
        }
        yield code;
      }
      default -> accountCurrency;
    };
  }

  // The template's mapping first; a cell already naming a transaction type is taken as it is if
  // the template class allows it; anything else (or nothing) goes by the amount's sign.
  private static String transactionTypeOf(
      String value, String column, Map<String, String> typeMapping, BigDecimal amount) {
    if (value != null) {
      String mapped = typeMapping.get(normalize(value));
      if (mapped != null) {
        return mapped;
      }
      String named = value.toUpperCase(Locale.ROOT);
      if (ImportTemplateValues.CASH_TRANSACTION_TYPES.contains(named)) {
        return named;
      }
      if (ImportTemplateValues.TRANSACTION_TYPES.contains(named)) {
        throw rowError(ImportRowErrorValues.TYPE_NOT_ALLOWED, ARG_COLUMN, column, ARG_VALUE, value);
      }
    }
    return amount.signum() < 0
        ? ImportTemplateValues.FALLBACK_TYPE_NEGATIVE
        : ImportTemplateValues.FALLBACK_TYPE_NOT_NEGATIVE;
  }

  // --- helpers ---------------------------------------------------------------------------

  // java.time's 'y' is the year of era, which STRICT resolution cannot use without an era; banks'
  // patterns are written with it, so it is read as 'u' (the proleptic year) outside quoted text.
  private static DateTimeFormatter dateFormatter(String pattern) {
    StringBuilder converted = new StringBuilder(pattern.length());
    boolean quoted = false;
    for (char character : pattern.toCharArray()) {
      boolean quote = character == '\'';
      if (quote) {
        quoted = !quoted;
      }
      converted.append(!quoted && character == 'y' ? 'u' : character);
    }
    return DateTimeFormatter.ofPattern(converted.toString(), Locale.ROOT)
        .withResolverStyle(ResolverStyle.STRICT);
  }

  private static Map<String, String> normalizedTypeMapping(Map<String, String> typeMapping) {
    Map<String, String> normalized = new HashMap<>();
    typeMapping.forEach((source, type) -> normalized.put(normalize(source), type));
    return normalized;
  }

  private static String stripLeadingZeros(String digits) {
    int start = 0;
    while (start < digits.length() && digits.charAt(start) == '0') {
      start++;
    }
    return digits.substring(start);
  }

  // Drops trailing zeros, but keeps at least minLength digits (the scale the file wrote).
  private static String stripTrailingZeros(String digits, int minLength) {
    int end = digits.length();
    while (end > minLength && digits.charAt(end - 1) == '0') {
      end--;
    }
    return digits.substring(0, end);
  }

  private static String normalize(String text) {
    return text.strip().toLowerCase(Locale.ROOT);
  }

  private static boolean isSingleCharacter(String text) {
    return text != null && text.length() == 1;
  }

  private static boolean isBlank(CSVRecord record) {
    for (String cell : record) {
      if (!cell.isBlank()) {
        return false;
      }
    }
    return true;
  }

  // Arguments are name/value pairs, kept in order: the message's {0}, {1}, ... follow it.
  private static ImportRowRejectedException rowError(String code, String... namesAndValues) {
    return new ImportRowRejectedException(code, rowArgs(namesAndValues));
  }

  private static Map<String, String> rowArgs(String... namesAndValues) {
    Map<String, String> args = new LinkedHashMap<>();
    for (int i = 0; i < namesAndValues.length; i += 2) {
      String value = namesAndValues[i + 1];
      args.put(
          namesAndValues[i],
          value.length() > MAX_ECHOED_VALUE_LENGTH
              ? value.substring(0, MAX_ECHOED_VALUE_LENGTH)
              : value);
    }
    return args;
  }

  private static ImportFileRejectedException tooManyRows() {
    return fileError(
            ApiErrorCode.IMPORT_FILE_TOO_MANY_ROWS,
            "An import file may hold at most " + MAX_DATA_ROWS + " data rows.")
        .withProperty("maxRows", MAX_DATA_ROWS);
  }

  private static ImportFileRejectedException fileError(String code, String detail) {
    return new ImportFileRejectedException(code, detail);
  }

  private static ApiException invalid(String field, String detail) {
    ApiException exception =
        new ApiException(
            HttpStatus.UNPROCESSABLE_CONTENT, ApiErrorCode.IMPORT_TEMPLATE_INVALID, detail);
    exception.getBody().setProperty("field", field);
    return exception;
  }

  private static ApiException unsupported(String detail) {
    return new ApiException(
        HttpStatus.UNPROCESSABLE_CONTENT, ApiErrorCode.IMPORT_TEMPLATE_UNSUPPORTED, detail);
  }
}
