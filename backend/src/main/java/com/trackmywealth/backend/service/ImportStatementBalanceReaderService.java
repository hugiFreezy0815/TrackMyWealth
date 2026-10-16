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
  private static final String STATEMENT_DATE_FIELD = FIELD + ".statementDatePattern";
  private static final Set<String> GROUPS =
      Set.of(
          ImportStatementBalanceRule.DATE_GROUP,
          ImportStatementBalanceRule.AMOUNT_GROUP,
          ImportStatementBalanceRule.CURRENCY_GROUP,
          ImportStatementBalanceRule.SIGN_GROUP);
  private static final String MINUS = "-";
  private static final String PLUS = "+";
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
    boolean statementDate = rule.statementDatePattern() != null;
    Set<String> groups = compile(rule.pattern(), PATTERN_FIELD).namedGroups().keySet();
    if (!groups.contains(ImportStatementBalanceRule.AMOUNT_GROUP)
        || !(statementDate || groups.contains(ImportStatementBalanceRule.DATE_GROUP))) {
      throw invalid(
          PATTERN_FIELD,
          "The pattern needs the named groups (?P<date>...) and (?P<amount>...); the date group"
              + " only when no statementDatePattern dates the balance.");
    }
    if (!GROUPS.containsAll(groups)) {
      throw invalid(
          PATTERN_FIELD, "The pattern's named groups can only be date, amount, currency and sign.");
    }
    requireDateFormat(rule.dateFormat(), FIELD + ".dateFormat");
    // #339: the date of a balance line without one, e.g. the end of the statement's period.
    if (statementDate
        && !Set.of(ImportStatementBalanceRule.DATE_GROUP)
            .equals(
                compile(rule.statementDatePattern(), STATEMENT_DATE_FIELD)
                    .namedGroups()
                    .keySet())) {
      throw invalid(STATEMENT_DATE_FIELD, "The pattern needs the one named group (?P<date>...).");
    }
    if (rule.statementDateFormat() != null && !statementDate) {
      throw invalid(
          FIELD + ".statementDateFormat", "A statement date format needs a statementDatePattern.");
    }
    requireDateFormat(rule.statementDateFormat(), FIELD + ".statementDateFormat");
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

  private static void requireDateFormat(String dateFormat, String field) {
    if (dateFormat != null
        && (dateFormat.length() > ImportStatementBalanceRule.MAX_DATE_FORMAT_LENGTH
            || !ImportFileParserService.isValidDateFormat(dateFormat))) {
      throw invalid(field, "The date format is not a valid date pattern.");
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
    Set<String> groups = pattern.namedGroups().keySet();
    boolean hasCurrency = groups.contains(ImportStatementBalanceRule.CURRENCY_GROUP);
    String dateFormat = rule.dateFormat() == null ? template.dateFormat() : rule.dateFormat();
    DateTimeFormatter formatter = ImportFileParserService.dateFormatter(dateFormat);
    boolean parentheses =
        ImportTemplateValues.AMOUNT_NEGATIVE_IN_PARENTHESES.equals(template.amountRepresentation());
    LocalDate statementDate = statementDate(lines, rule, dateFormat);
    List<ImportStatementBalances.Stated> balances = new ArrayList<>();
    List<String> unreadableCurrencies = new ArrayList<>();
    for (String line : lines) {
      Matcher matcher = pattern.matcher(line.strip());
      if (!matcher.find()) {
        continue;
      }
      String currency = hasCurrency ? currencyOf(matcher) : null;
      ImportStatementBalances.Stated stated =
          stated(matcher, groups, formatter, statementDate, template, parentheses);
      if (stated == null) {
        unreadableCurrencies.add(currency);
      } else {
        balances.add(stated);
      }
    }
    return new ImportStatementBalances(balances, unreadableCurrencies, template.isPdf());
  }

  // The line's currency when it can be read, else null: an unreadable line is then attributed to
  // it.
  private static String currencyOf(Matcher matcher) {
    String group = matcher.group(ImportStatementBalanceRule.CURRENCY_GROUP);
    String currency = group == null ? null : group.strip().toUpperCase(Locale.ROOT);
    return currency != null && isCurrencyCode(currency) ? currency : null;
  }

  // #339: the date of statementDatePattern's first match in the file whose date can be read, or
  // null when the rule has none or no match has a readable date - a balance line without a date
  // of its own then is unreadable.
  private static LocalDate statementDate(
      List<String> lines, ImportStatementBalanceRule rule, String balanceDateFormat) {
    if (rule.statementDatePattern() == null) {
      return null;
    }
    Pattern pattern = Re2Patterns.compile(rule.statementDatePattern());
    DateTimeFormatter formatter =
        ImportFileParserService.dateFormatter(
            rule.statementDateFormat() == null ? balanceDateFormat : rule.statementDateFormat());
    for (String line : lines) {
      Matcher matcher = pattern.matcher(line.strip());
      LocalDate date =
          matcher.find()
              ? parseDate(matcher.group(ImportStatementBalanceRule.DATE_GROUP), formatter)
              : null;
      if (date != null) {
        return date;
      }
    }
    return null;
  }

  private static LocalDate parseDate(String value, DateTimeFormatter formatter) {
    if (value == null) {
      return null;
    }
    try {
      return LocalDate.parse(value.strip(), formatter);
    } catch (DateTimeException e) {
      return null;
    }
  }

  // The balance one matched line states, or null when its date, amount, sign or currency is
  // unreadable. A line whose date group is absent or did not match takes the statement date. A
  // sign after an amount that is signed itself ("-7,25 +", "(7,25) -") is unreadable: the two
  // could contradict each other, and neither is the safer guess.
  private static ImportStatementBalances.Stated stated(
      Matcher matcher,
      Set<String> groups,
      DateTimeFormatter formatter,
      LocalDate statementDate,
      ImportTemplateDefinition template,
      boolean parentheses) {
    String date = optionalGroup(matcher, groups, ImportStatementBalanceRule.DATE_GROUP);
    String amount = matcher.group(ImportStatementBalanceRule.AMOUNT_GROUP);
    LocalDate parsedDate = date == null ? statementDate : parseDate(date, formatter);
    if (parsedDate == null || amount == null) {
      return null;
    }
    String group = optionalGroup(matcher, groups, ImportStatementBalanceRule.CURRENCY_GROUP);
    String currency = group == null ? null : group.strip().toUpperCase(Locale.ROOT);
    if (currency != null && !isCurrencyCode(currency)) {
      return null;
    }
    String sign = optionalGroup(matcher, groups, ImportStatementBalanceRule.SIGN_GROUP);
    if (sign != null && !MINUS.equals(sign.strip()) && !PLUS.equals(sign.strip())) {
      return null;
    }
    try {
      BigDecimal parsedAmount =
          ImportFileParserService.parseAmount(amount, FIELD, template, parentheses);
      if (sign != null) {
        if (parsedAmount.signum() < 0) {
          return null;
        }
        parsedAmount = MINUS.equals(sign.strip()) ? parsedAmount.negate() : parsedAmount;
      }
      return new ImportStatementBalances.Stated(parsedDate, parsedAmount, currency);
    } catch (ImportRowRejectedException e) {
      return null;
    }
  }

  // A named group's text, or null when the pattern has no such group or it did not take part.
  private static String optionalGroup(Matcher matcher, Set<String> groups, String name) {
    return groups.contains(name) ? matcher.group(name) : null;
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
