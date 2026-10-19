package com.trackmywealth.backend.service;

import com.google.re2j.Matcher;
import com.google.re2j.Pattern;
import com.trackmywealth.backend.dto.ImportHoldingsRule;
import com.trackmywealth.backend.dto.ImportHoldingsRule.ImportHoldingsColumns;
import com.trackmywealth.backend.dto.ImportHoldingsStatement;
import com.trackmywealth.backend.dto.ImportHoldingsValues;
import com.trackmywealth.backend.dto.ImportPdfLayout;
import com.trackmywealth.backend.dto.ImportStatementBalanceRule;
import com.trackmywealth.backend.dto.ImportTemplateDefinition;
import com.trackmywealth.backend.dto.ImportTemplateValues;
import com.trackmywealth.backend.dto.SpreadsheetCell;
import com.trackmywealth.backend.error.ApiErrorCode;
import com.trackmywealth.backend.error.ApiException;
import com.trackmywealth.backend.error.ImportFileRejectedException;
import com.trackmywealth.backend.error.ImportRowRejectedException;
import com.trackmywealth.backend.validation.IsinValidator;
import com.trackmywealth.backend.validation.Re2Patterns;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * US-07-13 (#273): reads a holdings statement through its {@code SNAPSHOT} template's {@link
 * ImportHoldingsRule}, and checks such a rule. Stateless: which security a position is and what
 * becomes a snapshot is decided against the workspace and the account ({@link
 * ImportHoldingsService}).
 *
 * <p>Fail closed: a line the rule takes for a position or a cash balance but cannot read (an amount
 * out of range, an ISIN with a wrong check digit, a position without a currency, a second line of
 * the same position) is kept as an {@code ERROR} line, which the commit refuses - never dropped,
 * since the snapshot would then miss what it held. Each number is bounded by the column it is
 * stored in: quantity {@code NUMERIC(28,10)}, price {@code NUMERIC(20,10)}, value and cash {@code
 * NUMERIC(20,4)}.
 */
@Service
public class ImportHoldingsReaderService {

  static final String FIELD = "holdingsRule";
  private static final String COLUMNS_FIELD = FIELD + ".columns";

  /** At most this many positions, cash balances and unreadable lines per statement. */
  static final int MAX_LINES = 2_000;

  private static final int MAX_RAW_TEXT = 500;
  private static final int MAX_NAME = 200;
  private static final int QUANTITY_INTEGER_DIGITS = 18;
  private static final int QUANTITY_FRACTION_DIGITS = 10;
  private static final int PRICE_INTEGER_DIGITS = 10;
  private static final int PRICE_FRACTION_DIGITS = 10;
  private static final int MONEY_INTEGER_DIGITS = 16;
  private static final int MONEY_FRACTION_DIGITS = 4;
  private static final int CURRENCY_CODE_LENGTH = 3;
  // A statement rounds; a bond's price is a percentage of its nominal value.
  private static final BigDecimal MISMATCH_TOLERANCE = new BigDecimal("0.01");
  private static final BigDecimal MISMATCH_FLOOR = new BigDecimal("0.05");
  private static final BigDecimal PERCENT = new BigDecimal("100");
  private static final Pattern TICKER = Pattern.compile("[A-Z0-9][A-Z0-9.:-]{0,19}");
  private static final Pattern WHITESPACE = Pattern.compile("\\s+");
  // The cells of a spreadsheet holdings rule, by their index in a resolved column array.
  private static final int ISIN_CELL = 0;
  private static final int SYMBOL_CELL = 1;
  private static final int NAME_CELL = 2;
  private static final int QUANTITY_CELL = 3;
  private static final int PRICE_CELL = 4;
  private static final int VALUE_CELL = 5;
  private static final int CURRENCY_CELL = 6;
  private static final int CASH_AMOUNT_CELL = 7;
  private static final Set<String> POSITION_GROUPS =
      Set.of(
          ImportHoldingsRule.ISIN_GROUP,
          ImportHoldingsRule.SYMBOL_GROUP,
          ImportHoldingsRule.NAME_GROUP,
          ImportHoldingsRule.QUANTITY_GROUP,
          ImportHoldingsRule.PRICE_GROUP,
          ImportHoldingsRule.VALUE_GROUP,
          ImportHoldingsRule.CURRENCY_GROUP);
  private static final Set<String> CASH_GROUPS =
      Set.of(ImportHoldingsRule.CURRENCY_GROUP, ImportHoldingsRule.AMOUNT_GROUP);

  private final PdfImportReaderService pdfReader;
  private final SpreadsheetImportReaderService spreadsheetReader;

  public ImportHoldingsReaderService(
      PdfImportReaderService pdfReader, SpreadsheetImportReaderService spreadsheetReader) {
    this.pdfReader = pdfReader;
    this.spreadsheetReader = spreadsheetReader;
  }

  // --- the rule ----------------------------------------------------------------------------

  /**
   * Checks a {@code SNAPSHOT} template's holdings rule against its file format.
   *
   * @param headerColumns a spreadsheet sample's header cells, to check the columns by name against;
   *     {@code null} skips that check
   * @throws ApiException 422 {@code IMPORT_TEMPLATE_INVALID} naming the field at fault
   */
  public static void validate(ImportTemplateDefinition template, List<String> headerColumns) {
    ImportHoldingsRule rule = template.holdingsRule();
    if (rule == null) {
      throw invalid(FIELD, "A SNAPSHOT template needs a holdings rule.");
    }
    if (template.isPdf()) {
      validatePdf(rule);
    } else if (template.isSpreadsheet()) {
      validateSpreadsheet(template, rule, headerColumns);
    } else {
      throw invalid(
          "fileFormat", "A holdings statement is read from a PDF (PDF_TEXT) or a SPREADSHEET.");
    }
    if (rule.dateFormat() != null
        && (rule.dateFormat().length() > ImportStatementBalanceRule.MAX_DATE_FORMAT_LENGTH
            || !ImportFileParserService.isValidDateFormat(rule.dateFormat()))) {
      throw invalid(FIELD + ".dateFormat", "The date format is not a valid date pattern.");
    }
  }

  private static void validatePdf(ImportHoldingsRule rule) {
    String marker = rule.documentMarker();
    if (marker == null || marker.isBlank() || marker.length() > ImportPdfLayout.MAX_MARKER_LENGTH) {
      throw invalid(
          FIELD + ".documentMarker",
          "The document marker is text every statement of this layout contains.");
    }
    requireGroups(
        rule.datePattern(), FIELD + ".datePattern", Set.of(ImportHoldingsRule.DATE_GROUP));
    if (rule.sectionPattern() != null) {
      requireGroups(
          rule.sectionPattern(),
          FIELD + ".sectionPattern",
          Set.of(ImportHoldingsRule.CURRENCY_GROUP));
    }
    List<String> positions = rule.positionPatterns();
    if (positions.isEmpty() || positions.size() > ImportHoldingsRule.MAX_POSITION_PATTERNS) {
      throw invalid(
          FIELD + ".positionPatterns",
          "Name 1 to " + ImportHoldingsRule.MAX_POSITION_PATTERNS + " position patterns.");
    }
    for (int i = 0; i < positions.size(); i++) {
      String field = FIELD + ".positionPatterns[" + i + "]";
      Set<String> groups = compile(positions.get(i), field).namedGroups().keySet();
      if (!POSITION_GROUPS.containsAll(groups)
          || !groups.contains(ImportHoldingsRule.QUANTITY_GROUP)
          || !groups.contains(ImportHoldingsRule.VALUE_GROUP)
          || !groups.contains(ImportHoldingsRule.ISIN_GROUP)
              && !groups.contains(ImportHoldingsRule.SYMBOL_GROUP)) {
        throw invalid(
            field,
            "A position pattern needs the named groups quantity and value, isin and/or symbol,"
                + " and may have price, name and currency - no others.");
      }
    }
    if (rule.cashPattern() != null) {
      requireGroups(rule.cashPattern(), FIELD + ".cashPattern", CASH_GROUPS);
    }
    if (rule.columns() != null) {
      throw invalid(COLUMNS_FIELD, "Only a spreadsheet's holdings are read by columns.");
    }
  }

  private static void validateSpreadsheet(
      ImportTemplateDefinition template, ImportHoldingsRule rule, List<String> headerColumns) {
    if (rule.documentMarker() != null
        || rule.datePattern() != null
        || rule.dateFormat() != null
        || rule.sectionPattern() != null
        || !rule.positionPatterns().isEmpty()
        || rule.cashPattern() != null) {
      throw invalid(
          FIELD,
          "A spreadsheet's holdings are read by columns only; its date is entered at upload.");
    }
    ImportHoldingsColumns columns = rule.columns();
    if (columns == null || !columns.mapsPositions() && !columns.mapsCash()) {
      throw invalid(
          COLUMNS_FIELD, "Map the columns of a position (quantity, value), of cash, or both.");
    }
    if (template.headerRowIndex() == ImportTemplateValues.NO_HEADER_ROW) {
      throw invalid("headerRowIndex", "A spreadsheet's columns are named by its header row.");
    }
    if (columns.mapsPositions()
        && (columns.quantity() == null
            || columns.value() == null
            || columns.isin() == null && columns.symbol() == null)) {
      throw invalid(
          COLUMNS_FIELD,
          "A position needs the quantity, value and currency columns, and isin and/or symbol.");
    }
    if (columns.currency() == null) {
      throw invalid(FIELD + ".columns.currency", "Map the currency column.");
    }
    List<String> mapped = mappedColumns(columns);
    for (String column : mapped) {
      if (column.isBlank()) {
        throw invalid(COLUMNS_FIELD, "A mapped column must not be blank.");
      }
      if (headerColumns != null && ImportFileParserService.columnIndex(column, headerColumns) < 0) {
        throw invalid(
            COLUMNS_FIELD, "The sample's header has no single column named " + column + ".");
      }
    }
  }

  /** The header cells a spreadsheet holdings rule maps, in field order, each once. */
  public static List<String> mappedColumns(ImportHoldingsColumns columns) {
    List<String> mapped = new ArrayList<>();
    for (String column :
        new String[] {
          columns.isin(),
          columns.symbol(),
          columns.name(),
          columns.quantity(),
          columns.price(),
          columns.value(),
          columns.currency(),
          columns.cashAmount()
        }) {
      if (column != null && !mapped.contains(column)) {
        mapped.add(column);
      }
    }
    return mapped;
  }

  private static void requireGroups(String pattern, String field, Set<String> groups) {
    if (!groups.equals(compile(pattern, field).namedGroups().keySet())) {
      throw invalid(field, "The pattern needs exactly the named groups " + groups + ".");
    }
  }

  private static Pattern compile(String pattern, String field) {
    if (pattern == null
        || pattern.isBlank()
        || pattern.length() > ImportPdfLayout.MAX_PATTERN_LENGTH) {
      throw invalid(
          field,
          "A pattern is required, with at most "
              + ImportPdfLayout.MAX_PATTERN_LENGTH
              + " characters.");
    }
    try {
      return Re2Patterns.compile(pattern);
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

  /**
   * A spreadsheet's header cells, as a SNAPSHOT spreadsheet template reads them - for detection,
   * which compares them with the template's header fingerprint.
   *
   * @throws ImportFileRejectedException a file-level code when the file is no workbook this
   *     template can read
   */
  public List<String> readHeader(byte[] content, ImportTemplateDefinition template) {
    List<List<SpreadsheetCell>> rows = spreadsheetReader.readRows(content);
    int headerAt = template.preambleRowCount() + template.headerRowIndex();
    if (headerAt >= rows.size()) {
      throw new ImportFileRejectedException(
          ApiErrorCode.IMPORT_FILE_EMPTY, "The sheet ends before its header row.");
    }
    return rows.get(headerAt).stream().map(SpreadsheetCell::text).toList();
  }

  // --- detection ---------------------------------------------------------------------------

  /** Whether a PDF's text holds the rule's document marker and a readable statement date. */
  public static boolean isStatementOf(String pdfText, ImportTemplateDefinition template) {
    ImportHoldingsRule rule = template.holdingsRule();
    return rule != null
        && rule.documentMarker() != null
        && pdfText.contains(rule.documentMarker())
        && statementDate(List.of(pdfText.split("\\R")), template) != null;
  }

  // --- reading -----------------------------------------------------------------------------

  /**
   * What {@code content} says, read with {@code template} (valid, {@link #validate}).
   *
   * @param memberDate the date the member entered at upload: required for a spreadsheet, which
   *     states none; a PDF's own date is never overridden
   * @throws ImportFileRejectedException 422 {@code IMPORT_FILE_MALFORMED} for a PDF without a
   *     readable statement date, {@code IMPORT_FILE_NO_DATA_ROWS} for a statement without any
   *     position or cash line, {@code IMPORT_FILE_TOO_MANY_ROWS} past {@value #MAX_LINES} lines,
   *     and the readers' file-level codes
   */
  public ImportHoldingsStatement read(
      byte[] content, ImportTemplateDefinition template, LocalDate memberDate) {
    ImportHoldingsStatement statement =
        template.isSpreadsheet()
            ? readSpreadsheet(content, template, memberDate)
            : readPdf(content, template);
    if (statement.lines().isEmpty()) {
      throw new ImportFileRejectedException(
          ApiErrorCode.IMPORT_FILE_NO_DATA_ROWS,
          "The statement holds no position or cash balance this template reads.");
    }
    return statement;
  }

  private ImportHoldingsStatement readPdf(byte[] content, ImportTemplateDefinition template) {
    String text =
        template.isOcr() ? pdfReader.readScannedText(content) : pdfReader.readTextLayer(content);
    List<String> lines = List.of(text.split("\\R"));
    LocalDate date = statementDate(lines, template);
    if (date == null) {
      throw new ImportFileRejectedException(
          ApiErrorCode.IMPORT_FILE_MALFORMED,
          "The statement states no date this template can read.");
    }
    ImportHoldingsRule rule = template.holdingsRule();
    Pattern section =
        rule.sectionPattern() == null ? null : Re2Patterns.compile(rule.sectionPattern());
    List<Pattern> positions = rule.positionPatterns().stream().map(Re2Patterns::compile).toList();
    Pattern cash = rule.cashPattern() == null ? null : Re2Patterns.compile(rule.cashPattern());
    List<ImportHoldingsStatement.Line> read = new ArrayList<>();
    Set<String> seen = new HashSet<>();
    String sectionCurrency = null;
    for (String raw : lines) {
      String line = raw.strip();
      Matcher sectionMatch = section == null ? null : section.matcher(line);
      if (sectionMatch != null && sectionMatch.find()) {
        sectionCurrency = currencyCode(sectionMatch.group(ImportHoldingsRule.CURRENCY_GROUP));
        continue;
      }
      Matcher position = firstMatch(positions, line);
      if (position != null) {
        add(read, seen, pdfPosition(position, line, sectionCurrency, template, read.size() + 1));
        continue;
      }
      Matcher cashMatch = cash == null ? null : cash.matcher(line);
      if (cashMatch != null && cashMatch.find()) {
        add(
            read,
            seen,
            cashLine(
                read.size() + 1,
                line,
                cashMatch.group(ImportHoldingsRule.CURRENCY_GROUP),
                SpreadsheetCell.BLANK,
                cashMatch.group(ImportHoldingsRule.AMOUNT_GROUP),
                template));
      }
    }
    return new ImportHoldingsStatement(date, ImportHoldingsValues.DATE_STATEMENT, read);
  }

  private ImportHoldingsStatement readSpreadsheet(
      byte[] content, ImportTemplateDefinition template, LocalDate memberDate) {
    if (memberDate == null) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST,
          "snapshotDate is required: a spreadsheet does not state the date of its holdings.");
    }
    List<List<SpreadsheetCell>> rows = spreadsheetReader.readRows(content);
    int headerAt = template.preambleRowCount() + template.headerRowIndex();
    if (headerAt >= rows.size()) {
      throw new ImportFileRejectedException(
          ApiErrorCode.IMPORT_FILE_EMPTY, "The sheet ends before its header row.");
    }
    List<String> header = rows.get(headerAt).stream().map(SpreadsheetCell::text).toList();
    ImportHoldingsColumns columns = template.holdingsRule().columns();
    for (String column : mappedColumns(columns)) {
      if (ImportFileParserService.columnIndex(column, header) < 0) {
        throw new ImportFileRejectedException(
                ApiErrorCode.IMPORT_TEMPLATE_MISMATCH,
                "The sheet's header has no single column named " + column + ".")
            .withProperty("column", column);
      }
    }
    int[] at = {
      indexOf(columns.isin(), header),
      indexOf(columns.symbol(), header),
      indexOf(columns.name(), header),
      indexOf(columns.quantity(), header),
      indexOf(columns.price(), header),
      indexOf(columns.value(), header),
      indexOf(columns.currency(), header),
      indexOf(columns.cashAmount(), header)
    };
    List<ImportHoldingsStatement.Line> read = new ArrayList<>();
    Set<String> seen = new HashSet<>();
    int end = rows.size() - template.trailingSummaryRowCount();
    for (int index = headerAt + 1; index < end; index++) {
      ImportHoldingsStatement.Line line = sheetLine(rows.get(index), at, template, read.size() + 1);
      if (line != null) {
        add(read, seen, line);
      }
    }
    return new ImportHoldingsStatement(memberDate, ImportHoldingsValues.DATE_MEMBER, read);
  }

  // A mapped column's index in the header (checked to exist), or -1 for a column not mapped.
  private static int indexOf(String column, List<String> header) {
    return column == null ? -1 : ImportFileParserService.columnIndex(column, header);
  }

  // Appends a line, bounded; a second position of the same ISIN (or, without one, symbol) is an
  // ERROR line: the statement lists each position once, and a snapshot holds each security once.
  private static void add(
      List<ImportHoldingsStatement.Line> lines,
      Set<String> seen,
      ImportHoldingsStatement.Line line) {
    if (lines.size() >= MAX_LINES) {
      throw new ImportFileRejectedException(
              ApiErrorCode.IMPORT_FILE_TOO_MANY_ROWS,
              "A holdings statement may have at most " + MAX_LINES + " positions and balances.")
          .withProperty("maxRows", MAX_LINES);
    }
    if (ImportHoldingsValues.KIND_POSITION.equals(line.kind())
        && !seen.add(line.isin() == null ? "symbol:" + line.symbol() : "isin:" + line.isin())) {
      lines.add(
          ImportHoldingsStatement.Line.error(
              line.rowNumber(),
              line.isin() == null ? line.symbol() : line.isin(),
              ImportHoldingsValues.ERROR_DUPLICATE));
      return;
    }
    lines.add(line);
  }

  // A data row with a quantity is a position; one with a cash amount and a currency code a cash
  // balance; anything else (a group heading, a subtotal, a total in "Gesamt CHF") is passed over.
  private static ImportHoldingsStatement.Line sheetLine(
      List<SpreadsheetCell> row, int[] at, ImportTemplateDefinition template, int number) {
    SpreadsheetCell quantity = cell(row, at[QUANTITY_CELL]);
    if (at[QUANTITY_CELL] >= 0 && !quantity.isBlank()) {
      String text = rawText(row);
      try {
        String isin = identifier(cell(row, at[ISIN_CELL]).text());
        String symbol = identifier(cell(row, at[SYMBOL_CELL]).text());
        if (isin == null && symbol == null) {
          return ImportHoldingsStatement.Line.error(
              number, text, ImportHoldingsValues.ERROR_IDENTIFIER_INVALID);
        }
        return position(
            number,
            text,
            isin,
            symbol,
            cell(row, at[NAME_CELL]).text(),
            quantity,
            cell(row, at[PRICE_CELL]),
            cell(row, at[VALUE_CELL]),
            currencyCode(cell(row, at[CURRENCY_CELL]).text()),
            template);
      } catch (ImportRowRejectedException e) {
        return ImportHoldingsStatement.Line.error(number, text, e.getCode());
      }
    }
    SpreadsheetCell amount = cell(row, at[CASH_AMOUNT_CELL]);
    String currency = currencyCode(cell(row, at[CURRENCY_CELL]).text());
    if (at[CASH_AMOUNT_CELL] >= 0 && !amount.isBlank() && currency != null) {
      return cashLine(number, rawText(row), currency, amount, amount.text(), template);
    }
    return null;
  }

  private static ImportHoldingsStatement.Line pdfPosition(
      Matcher matcher,
      String line,
      String sectionCurrency,
      ImportTemplateDefinition template,
      int number) {
    try {
      String isinText = group(matcher, ImportHoldingsRule.ISIN_GROUP);
      String symbolText = group(matcher, ImportHoldingsRule.SYMBOL_GROUP);
      String isin = isinText == null ? null : identifier(isinText);
      String symbol = symbolText == null ? null : identifier(symbolText);
      if (isin == null && symbol == null) {
        return ImportHoldingsStatement.Line.error(
            number, line, ImportHoldingsValues.ERROR_IDENTIFIER_INVALID);
      }
      String ownCurrency = group(matcher, ImportHoldingsRule.CURRENCY_GROUP);
      return position(
          number,
          line,
          isin,
          symbol,
          group(matcher, ImportHoldingsRule.NAME_GROUP),
          text(group(matcher, ImportHoldingsRule.QUANTITY_GROUP)),
          text(group(matcher, ImportHoldingsRule.PRICE_GROUP)),
          text(group(matcher, ImportHoldingsRule.VALUE_GROUP)),
          ownCurrency == null ? sectionCurrency : currencyCode(ownCurrency),
          template);
    } catch (ImportRowRejectedException e) {
      return ImportHoldingsStatement.Line.error(number, line, e.getCode());
    }
  }

  private static ImportHoldingsStatement.Line position(
      int number,
      String rawText,
      String isin,
      String symbol,
      String name,
      SpreadsheetCell quantityCell,
      SpreadsheetCell priceCell,
      SpreadsheetCell valueCell,
      String currency,
      ImportTemplateDefinition template) {
    if (isin != null && !IsinValidator.isValidIsin(isin)) {
      return ImportHoldingsStatement.Line.error(
          number, rawText, ImportHoldingsValues.ERROR_IDENTIFIER_INVALID);
    }
    BigDecimal quantity =
        number(quantityCell, template, QUANTITY_INTEGER_DIGITS, QUANTITY_FRACTION_DIGITS);
    BigDecimal price =
        priceCell.isBlank()
            ? null
            : number(priceCell, template, PRICE_INTEGER_DIGITS, PRICE_FRACTION_DIGITS);
    BigDecimal value = number(valueCell, template, MONEY_INTEGER_DIGITS, MONEY_FRACTION_DIGITS);
    if (quantity.signum() <= 0 || value.signum() < 0 || price != null && price.signum() < 0) {
      return ImportHoldingsStatement.Line.error(
          number, rawText, ImportHoldingsValues.ERROR_VALUE_UNREADABLE);
    }
    if (currency == null) {
      return ImportHoldingsStatement.Line.error(
          number, rawText, ImportHoldingsValues.ERROR_CURRENCY_MISSING);
    }
    List<String> warnings =
        price != null && mismatched(quantity, price, value)
            ? List.of(ImportHoldingsValues.WARNING_VALUE_MISMATCH)
            : List.of();
    return ImportHoldingsStatement.Line.position(
        number, isin, symbol, name(name), quantity, price, value, currency, warnings);
  }

  private static ImportHoldingsStatement.Line cashLine(
      int number,
      String rawText,
      String currencyText,
      SpreadsheetCell numeric,
      String amountText,
      ImportTemplateDefinition template) {
    String currency = currencyCode(currencyText);
    if (currency == null) {
      return ImportHoldingsStatement.Line.error(
          number, rawText, ImportHoldingsValues.ERROR_CURRENCY_MISSING);
    }
    try {
      SpreadsheetCell amount =
          numeric.number() == null ? new SpreadsheetCell(amountText, null) : numeric;
      return ImportHoldingsStatement.Line.cash(
          number, currency, number(amount, template, MONEY_INTEGER_DIGITS, MONEY_FRACTION_DIGITS));
    } catch (ImportRowRejectedException e) {
      return ImportHoldingsStatement.Line.error(number, rawText, e.getCode());
    }
  }

  // A number cell is a binary double: rounded to the column's scale, as a spreadsheet displays it.
  // A text one is read with the template's separators, and refused past the column's scale.
  private static BigDecimal number(
      SpreadsheetCell cell, ImportTemplateDefinition template, int maxInteger, int maxFraction) {
    BigDecimal value;
    if (cell.number() != null) {
      value = cell.number().setScale(maxFraction, RoundingMode.HALF_UP).stripTrailingZeros();
      if (value.scale() < 0) {
        value = value.setScale(0);
      }
      if (value.precision() - value.scale() > maxInteger) {
        throw new ImportRowRejectedException(ImportHoldingsValues.ERROR_VALUE_UNREADABLE, Map.of());
      }
      return value;
    }
    if (cell.text().isBlank()) {
      throw new ImportRowRejectedException(ImportHoldingsValues.ERROR_VALUE_UNREADABLE, Map.of());
    }
    try {
      return ImportFileParserService.parseNumber(
          cell.text(), FIELD, template, maxInteger, maxFraction);
    } catch (ImportRowRejectedException e) {
      throw new ImportRowRejectedException(
          ImportHoldingsValues.ERROR_VALUE_UNREADABLE, e.getArgs(), e);
    }
  }

  // Neither quantity x price nor a hundredth of it (a bond quoted in percent) is the value, within
  // 1 % or 0.05.
  static boolean mismatched(BigDecimal quantity, BigDecimal price, BigDecimal value) {
    BigDecimal tolerance = value.abs().multiply(MISMATCH_TOLERANCE).max(MISMATCH_FLOOR);
    BigDecimal product = quantity.multiply(price);
    return product.subtract(value).abs().compareTo(tolerance) > 0
        && product.divide(PERCENT).subtract(value).abs().compareTo(tolerance) > 0;
  }

  // The first readable date of datePattern, or null.
  private static LocalDate statementDate(List<String> lines, ImportTemplateDefinition template) {
    ImportHoldingsRule rule = template.holdingsRule();
    if (rule.datePattern() == null) {
      return null;
    }
    Pattern pattern = Re2Patterns.compile(rule.datePattern());
    DateTimeFormatter formatter =
        ImportFileParserService.dateFormatter(
            rule.dateFormat() == null ? template.dateFormat() : rule.dateFormat());
    for (String line : lines) {
      Matcher matcher = pattern.matcher(line.strip());
      // A match whose date is unreadable dates nothing; a later one may.
      LocalDate date =
          matcher.find()
              ? parseDate(matcher.group(ImportHoldingsRule.DATE_GROUP), formatter)
              : null;
      if (date != null) {
        return date;
      }
    }
    return null;
  }

  private static LocalDate parseDate(String value, DateTimeFormatter formatter) {
    try {
      return LocalDate.parse(value.strip(), formatter);
    } catch (DateTimeException e) {
      return null;
    }
  }

  private static Matcher firstMatch(List<Pattern> patterns, String line) {
    for (Pattern pattern : patterns) {
      Matcher matcher = pattern.matcher(line);
      if (matcher.find()) {
        return matcher;
      }
    }
    return null;
  }

  // A named group's text, or null when the pattern has no such group or it did not take part.
  private static String group(Matcher matcher, String name) {
    return matcher.pattern().namedGroups().containsKey(name) ? matcher.group(name) : null;
  }

  private static SpreadsheetCell text(String value) {
    return value == null ? SpreadsheetCell.BLANK : new SpreadsheetCell(value, null);
  }

  private static SpreadsheetCell cell(List<SpreadsheetCell> row, int index) {
    return index < 0 || index >= row.size() ? SpreadsheetCell.BLANK : row.get(index);
  }

  // An ISIN or ticker as stored: upper case without spaces; null when blank. A text that is
  // neither shape is refused (ERROR_IDENTIFIER_INVALID).
  private static String identifier(String text) {
    if (text == null || text.isBlank()) {
      return null;
    }
    String normalized = WHITESPACE.matcher(text.strip()).replaceAll("").toUpperCase(Locale.ROOT);
    if (!TICKER.matcher(normalized).matches()) {
      throw new ImportRowRejectedException(ImportHoldingsValues.ERROR_IDENTIFIER_INVALID, Map.of());
    }
    return normalized;
  }

  // A currency code's shape, upper case; null otherwise.
  private static String currencyCode(String text) {
    if (text == null) {
      return null;
    }
    String code = text.strip().toUpperCase(Locale.ROOT);
    return code.length() == CURRENCY_CODE_LENGTH && code.chars().allMatch(c -> c >= 'A' && c <= 'Z')
        ? code
        : null;
  }

  private static String name(String text) {
    if (text == null || text.isBlank()) {
      return null;
    }
    String name = WHITESPACE.matcher(text.strip()).replaceAll(" ");
    return name.length() > MAX_NAME ? name.substring(0, MAX_NAME) : name;
  }

  private static String rawText(List<SpreadsheetCell> row) {
    String text = String.join(" | ", row.stream().map(SpreadsheetCell::text).toList());
    return text.length() > MAX_RAW_TEXT ? text.substring(0, MAX_RAW_TEXT) : text;
  }

  private static ApiException invalid(String field, String detail) {
    ApiException exception =
        new ApiException(
            HttpStatus.UNPROCESSABLE_CONTENT, ApiErrorCode.IMPORT_TEMPLATE_INVALID, detail);
    exception.getBody().setProperty("field", field);
    return exception;
  }
}
