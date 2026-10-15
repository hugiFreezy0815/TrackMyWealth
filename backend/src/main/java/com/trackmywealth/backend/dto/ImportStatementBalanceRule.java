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
 */
public record ImportStatementBalanceRule(
    @Schema(
            description =
                "RE2 pattern with the named groups date and amount, and optionally currency, e.g."
                    + " Kontostand vom (?P<date>\\S+): (?P<amount>\\S+)")
        @Size(max = ImportPdfLayout.MAX_PATTERN_LENGTH)
        String pattern,
    @Schema(description = "Optional: the date's format, if not the template's date format.")
        @Size(max = MAX_DATE_FORMAT_LENGTH)
        String dateFormat) {

  public static final String DATE_GROUP = "date";
  public static final String AMOUNT_GROUP = "amount";
  public static final String CURRENCY_GROUP = "currency";
  public static final int MAX_DATE_FORMAT_LENGTH = 30;
}
