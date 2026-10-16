package com.trackmywealth.backend.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;

/**
 * US-07-12: where an import file states the account's balance, e.g. a CSV preamble line {@code
 * Kontostand vom 31.01.2031: 1.111,11 €} or a PDF line {@code Saldo per 31.01.2031 1'246.05 CHF}.
 *
 * <p>{@code pattern} is an RE2 pattern with the named groups {@value #DATE_GROUP} and {@value
 * #AMOUNT_GROUP}, and optionally {@value #CURRENCY_GROUP}. It is searched in every line of a CSV
 * file before its header row and in its trailing summary rows (cells joined by a space), or in
 * every text line of a PDF. The date is read with {@code dateFormat}, or the template's own date
 * format when it is {@code null}; the amount with the template's separators, signed as the account
 * holder sees it (negative: overdrawn, or owed on a card or loan).
 *
 * <p>#339, both optional:
 *
 * <ul>
 *   <li>a named group {@value #SIGN_GROUP}, {@code +} or {@code -}, for a statement that prints the
 *       sign after the amount ({@code 1.234,56 -}): it signs the amount's magnitude;
 *   <li>{@code statementDatePattern}, an RE2 pattern with the one named group {@value #DATE_GROUP}:
 *       its first match in the file is the date of a balance line without a date of its own ({@code
 *       Neuer Saldo 1.234,56 -}), e.g. the end of the statement's period. That date is read with
 *       {@code statementDateFormat}, or as a balance line's date when it is {@code null}. With it,
 *       {@code pattern} may leave its date group out, or out of some of its lines.
 * </ul>
 */
public record ImportStatementBalanceRule(
    @Schema(
            description =
                "RE2 pattern with the named groups date and amount, and optionally currency and"
                    + " sign, e.g. Kontostand vom (?P<date>\\S+): (?P<amount>\\S+). A statement"
                    + " with sections in several currencies needs the currency group: without it,"
                    + " every balance is taken to be in the account's currency. The date group may"
                    + " be left out when statementDatePattern gives the date.")
        @Size(max = ImportPdfLayout.MAX_PATTERN_LENGTH)
        String pattern,
    @Schema(description = "Optional: the date's format, if not the template's date format.")
        @Size(max = MAX_DATE_FORMAT_LENGTH)
        String dateFormat,
    @Schema(
            description =
                "Optional RE2 pattern with the one named group date, e.g. Abrechnung vom \\S+ bis"
                    + " (?P<date>\\S+): its first match in the file dates every balance line that"
                    + " has no date of its own.")
        @Size(max = ImportPdfLayout.MAX_PATTERN_LENGTH)
        String statementDatePattern,
    @Schema(
            description =
                "Optional: the format of statementDatePattern's date, if not the balance lines'.")
        @Size(max = MAX_DATE_FORMAT_LENGTH)
        String statementDateFormat) {

  public static final String DATE_GROUP = "date";
  public static final String AMOUNT_GROUP = "amount";
  public static final String CURRENCY_GROUP = "currency";
  public static final String SIGN_GROUP = "sign";
  public static final int MAX_DATE_FORMAT_LENGTH = 30;

  /** A rule whose every balance line states its own date (US-07-12). */
  public ImportStatementBalanceRule(String pattern, String dateFormat) {
    this(pattern, dateFormat, null, null);
  }
}
