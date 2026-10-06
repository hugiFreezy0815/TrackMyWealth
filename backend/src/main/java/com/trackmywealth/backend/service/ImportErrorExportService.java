package com.trackmywealth.backend.service;

import com.trackmywealth.backend.dto.ImportBatchValues;
import com.trackmywealth.backend.dto.ImportErrorExportResponse;
import com.trackmywealth.backend.dto.ImportTemplateValues;
import com.trackmywealth.backend.entity.Account;
import com.trackmywealth.backend.entity.ImportBatch;
import com.trackmywealth.backend.entity.ImportRowRaw;
import com.trackmywealth.backend.entity.ImportTemplate;
import com.trackmywealth.backend.repository.ImportRowRawRepository;
import com.trackmywealth.backend.repository.ImportTemplateRepository;
import com.trackmywealth.backend.security.AuthenticatedUserPrincipal;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * FR-IMP-012: the {@code ERROR} rows of an import batch as a CSV file the member can correct
 * (US-07-04) - each row's original cells, then {@code error_code} and {@code error_message} in the
 * caller's language - written with the source file's delimiter and encoding, so it opens like the
 * original. A PDF statement's rows are written comma-separated in UTF-8.
 *
 * <p>The cells are the bank's own texts - a payer chooses a transfer's description - and the file
 * is meant for a spreadsheet: a cell a spreadsheet would run as a formula gets a leading
 * apostrophe. That is one starting with a tab or a carriage return, or, after any leading blanks,
 * with {@code =}, {@code +}, {@code @} or a {@code -}, unless the whole cell is a plain negative
 * number such as {@code -1'234.50} ({@code -2+3+cmd|...} is a formula, not an amount).
 */
@Service
public class ImportErrorExportService {

  static final String ERROR_CODE_COLUMN = "error_code";
  static final String ERROR_MESSAGE_COLUMN = "error_message";
  private static final String PDF_DELIMITER = ",";
  private static final String PDF_ENCODING = ImportTemplateValues.ENCODING_UTF_8;
  private static final String LINE_END = "\r\n";
  private static final char QUOTE = '"';
  // A tab or carriage return first makes some spreadsheets read the rest as a formula.
  private static final String CONTROL_STARTS = "\t\r";
  // A negative amount as banks write it: digits with decimal and grouping marks, nothing else.
  private static final Pattern NEGATIVE_NUMBER =
      Pattern.compile("-[.,' \\u00A0]*\\d[\\d.,' \\u00A0]*");

  private final ImportBatchService batchService;
  private final ImportRowRawRepository rowRepository;
  private final ImportTemplateRepository templateRepository;

  public ImportErrorExportService(
      ImportBatchService batchService,
      ImportRowRawRepository rowRepository,
      ImportTemplateRepository templateRepository) {
    this.batchService = batchService;
    this.rowRepository = rowRepository;
    this.templateRepository = templateRepository;
  }

  /** The batch's error rows; a batch without any gives just the header line. */
  @Transactional(readOnly = true)
  public ImportErrorExportResponse export(
      UUID accountId, UUID batchId, AuthenticatedUserPrincipal actor) {
    Account account = batchService.requireEditableAccount(accountId, actor);
    ImportBatch batch = batchService.requireBatch(account, batchId, actor);
    Optional<ImportTemplate> template =
        Optional.ofNullable(batch.getTemplateId())
            .flatMap(id -> templateRepository.findVisibleTo(id, account.getWorkspace().getId()))
            .filter(t -> ImportTemplateValues.FORMAT_CSV.equals(t.getFileFormat()));
    String delimiter = template.map(ImportTemplate::getDelimiter).orElse(PDF_DELIMITER);
    String encoding = template.map(ImportTemplate::getEncoding).orElse(PDF_ENCODING);

    List<ImportRowRaw> errors =
        rowRepository.findByImportBatchIdAndParseStatusOrderByRowNumber(
            batch.getId(), ImportBatchValues.ROW_ERROR);
    List<Map<String, String>> cells = new ArrayList<>(errors.size());
    Set<String> columns = new LinkedHashSet<>();
    for (ImportRowRaw row : errors) {
      Map<String, String> raw = batchService.stringMap(row.getRawData());
      cells.add(raw);
      columns.addAll(raw.keySet());
    }

    Locale locale = LocaleContextHolder.getLocale();
    StringBuilder csv = new StringBuilder();
    List<String> header = new ArrayList<>(columns);
    header.add(ERROR_CODE_COLUMN);
    header.add(ERROR_MESSAGE_COLUMN);
    appendLine(csv, header, delimiter);
    for (int i = 0; i < errors.size(); i++) {
      ImportRowRaw row = errors.get(i);
      List<String> line = new ArrayList<>(header.size());
      for (String column : columns) {
        line.add(cells.get(i).getOrDefault(column, ""));
      }
      line.add(row.getErrorCode());
      line.add(
          batchService
              .message(row.getErrorCode(), batchService.stringMap(row.getErrorArgs()), locale)
              .message());
      appendLine(csv, line, delimiter);
    }
    return new ImportErrorExportResponse(
        csv.toString(), encoding, "import-" + batch.getId() + "-errors.csv");
  }

  private static void appendLine(StringBuilder csv, List<String> cells, String delimiter) {
    for (int i = 0; i < cells.size(); i++) {
      if (i > 0) {
        csv.append(delimiter);
      }
      csv.append(cell(cells.get(i), delimiter));
    }
    csv.append(LINE_END);
  }

  /** One CSV cell: defused as a formula, then quoted when it holds a delimiter, quote or break. */
  static String cell(String value, String delimiter) {
    String text = value == null ? "" : value;
    if (startsFormula(text)) {
      text = "'" + text;
    }
    boolean quote =
        text.contains(delimiter)
            || text.indexOf(QUOTE) >= 0
            || text.indexOf('\n') >= 0
            || text.indexOf('\r') >= 0;
    if (!quote) {
      return text;
    }
    return QUOTE + text.replace("\"", "\"\"") + QUOTE;
  }

  private static boolean startsFormula(String text) {
    if (text.isEmpty()) {
      return false;
    }
    if (CONTROL_STARTS.indexOf(text.charAt(0)) >= 0) {
      return true;
    }
    // Spreadsheets skip leading blanks: " =cmd" runs as well.
    String trimmed = text.stripLeading();
    if (trimmed.isEmpty()) {
      return false;
    }
    return switch (trimmed.charAt(0)) {
      case '=', '+', '@' -> true;
      // An amount such as -85,00 stays as it is; "-cmd" or "-2+3+cmd|..." would run as a formula.
      case '-' -> !NEGATIVE_NUMBER.matcher(trimmed).matches();
      default -> false;
    };
  }
}
