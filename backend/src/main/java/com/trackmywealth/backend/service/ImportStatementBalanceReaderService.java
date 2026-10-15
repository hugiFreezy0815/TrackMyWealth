package com.trackmywealth.backend.service;

import com.google.re2j.Matcher;
import com.google.re2j.Pattern;
import com.trackmywealth.backend.dto.ImportPdfLayout;
import com.trackmywealth.backend.dto.ImportStatementBalanceRule;
import com.trackmywealth.backend.dto.ImportStatementBalances;
import com.trackmywealth.backend.dto.ImportTemplateDefinition;
import com.trackmywealth.backend.dto.ImportTemplateValues;
import com.trackmywealth.backend.error.ApiErrorCode;
import com.trackmywealth.backend.error.ApiException;
import com.trackmywealth.backend.error.ImportRowRejectedException;
import com.trackmywealth.backend.validation.Re2Patterns;
import java.math.BigDecimal;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * US-07-12: reads the balances an import file states, per its template's {@link
 * ImportStatementBalanceRule}, and checks such a rule. Stateless: which of the balances becomes a
 * snapshot is decided against the account ({@link ImportStatementBalanceService}).
 *
 * <p>A line the rule's pattern matches but whose date, amount or currency cannot be read is
 * counted, never silently dropped: when no other balance is found, the batch says so ({@code
 * UNREADABLE}), so the member can correct the template.
 */
@Service
public class ImportStatementBalanceReaderService {

  static final String FIELD = "statementBalance";
  private static final String PATTERN_FIELD = FIELD + ".pattern";
  private static final Set<String> GROUPS =
      Set.of(
          ImportStatementBalanceRule.DATE_GROUP,
          ImportStatementBalanceRule.AMOUNT_GROUP,
          ImportStatementBalanceRule.CURRENCY_GROUP);
  private static final int CURRENCY_CODE_LENGTH = 3;

  /**
   * Checks the template's statement balance rule, if it has one.
   *
   * @throws ApiException 422 {@code IMPORT_TEMPLATE_INVALID} naming the field at fault
   */
  public void validate(ImportTemplateDefinition template) {
    ImportStatementBalanceRule rule = template.statementBalance();
    if (rule == null) {
      return;
    }
    String pattern = rule.pattern();
    if (pattern == null
        || pattern.isBlank()
        || pattern.length() > ImportPdfLayout.MAX_PATTERN_LENGTH) {
      throw invalid(
          PATTERN_FIELD,
          "A pattern is required, with at most "
              + ImportPdfLayout.MAX_PATTERN_LENGTH
              + " characters.");
    }
    Pattern compiled;
    try {
      compiled = Re2Patterns.compile(pattern);
    } catch (IllegalArgumentException e) {
      ApiException exception =
          new ApiException(
              HttpStatus.UNPROCESSABLE_CONTENT,
              ApiErrorCode.IMPORT_TEMPLATE_INVALID,
              e.getMessage(),
              e);
      exception.getBody().setProperty("field", PATTERN_FIELD);
      throw exception;
    }
    Set<String> groups = compiled.namedGroups().keySet();
    if (!groups.contains(ImportStatementBalanceRule.DATE_GROUP)
        || !groups.contains(ImportStatementBalanceRule.AMOUNT_GROUP)) {
      throw invalid(
          PATTERN_FIELD, "The pattern needs the named groups (?P<date>...) and (?P<amount>...).");
    }
    if (!GROUPS.containsAll(groups)) {
      throw invalid(
          PATTERN_FIELD, "The pattern's named groups can only be date, amount and currency.");
    }
    if (rule.dateFormat() != null
        && (rule.dateFormat().length() > ImportStatementBalanceRule.MAX_DATE_FORMAT_LENGTH
            || !ImportFileParserService.isValidDateFormat(rule.dateFormat()))) {
      throw invalid(FIELD + ".dateFormat", "The date format is not a valid date pattern.");
    }
    if (!template.isPdf()
        && template.preambleRowCount() == 0
        && template.headerRowIndex() <= 0
        && template.trailingSummaryRowCount() == 0) {
      throw invalid(
          FIELD,
          "The template has no lines before its header row or trailing summary rows to find the"
              + " balance in.");
    }
  }

  /**
   * The balances {@code lines} state, in their order; {@code null} for a template without a
   * statement balance rule. The template must be valid ({@link #validate}).
   */
  public ImportStatementBalances read(List<String> lines, ImportTemplateDefinition template) {
    ImportStatementBalanceRule rule = template.statementBalance();
    if (rule == null) {
      return null;
    }
    Pattern pattern = Re2Patterns.compile(rule.pattern());
    boolean hasCurrency =
        pattern.namedGroups().containsKey(ImportStatementBalanceRule.CURRENCY_GROUP);
    String dateFormat = rule.dateFormat() == null ? template.dateFormat() : rule.dateFormat();
    DateTimeFormatter formatter = ImportFileParserService.dateFormatter(dateFormat);
    boolean parentheses =
        ImportTemplateValues.AMOUNT_NEGATIVE_IN_PARENTHESES.equals(template.amountRepresentation());
    List<ImportStatementBalances.Stated> balances = new ArrayList<>();
    int unreadable = 0;
    for (String line : lines) {
      Matcher matcher = pattern.matcher(line.strip());
      if (!matcher.find()) {
        continue;
      }
      ImportStatementBalances.Stated stated =
          stated(matcher, hasCurrency, formatter, template, parentheses);
      if (stated == null) {
        unreadable++;
      } else {
        balances.add(stated);
      }
    }
    return new ImportStatementBalances(balances, unreadable);
  }

  // The balance one matched line states, or null when its date, amount or currency is unreadable.
  private static ImportStatementBalances.Stated stated(
      Matcher matcher,
      boolean hasCurrency,
      DateTimeFormatter formatter,
      ImportTemplateDefinition template,
      boolean parentheses) {
    String date = matcher.group(ImportStatementBalanceRule.DATE_GROUP);
    String amount = matcher.group(ImportStatementBalanceRule.AMOUNT_GROUP);
    if (date == null || amount == null) {
      return null;
    }
    String group = hasCurrency ? matcher.group(ImportStatementBalanceRule.CURRENCY_GROUP) : null;
    String currency = group == null ? null : group.strip().toUpperCase(Locale.ROOT);
    if (currency != null && !isCurrencyCode(currency)) {
      return null;
    }
    try {
      LocalDate parsedDate = LocalDate.parse(date.strip(), formatter);
      BigDecimal parsedAmount =
          ImportFileParserService.parseAmount(amount, FIELD, template, parentheses);
      return new ImportStatementBalances.Stated(parsedDate, parsedAmount, currency);
    } catch (DateTimeException | ImportRowRejectedException e) {
      return null;
    }
  }

  // An ISO 4217 code's shape: three letters A-Z.
  private static boolean isCurrencyCode(String value) {
    return value.length() == CURRENCY_CODE_LENGTH
        && value.chars().allMatch(c -> c >= 'A' && c <= 'Z');
  }

  private static ApiException invalid(String field, String detail) {
    ApiException exception =
        new ApiException(
            HttpStatus.UNPROCESSABLE_CONTENT, ApiErrorCode.IMPORT_TEMPLATE_INVALID, detail);
    exception.getBody().setProperty("field", field);
    return exception;
  }
}
