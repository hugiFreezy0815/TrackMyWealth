package com.trackmywealth.backend.error;

import java.util.List;

/**
 * The registry of every code an import row or holdings line carries in its {@code error} or {@code
 * warnings} ({@code ImportRowErrorResponse.code}; US-29-04, #374). Unlike a {@link ProblemCode} it
 * is no error response: the import goes on, and the row says why it is not imported or what to
 * check.
 *
 * <p>The codes themselves stay the string constants the parsers use and the database stores ({@code
 * ImportRowErrorValues}, {@code ImportBatchValues}, {@code ImportHoldingsValues}); this list adds
 * what the catalogue needs, and a test keeps both the same. The text is {@code
 * tmw.import.row.<CODE>}, with the row's {@code args} in order. Codes are part of the API contract,
 * with the same lifecycle as a {@link ProblemCode}: adding one is additive, removing one is a
 * declared break that adds its name to {@link #RETIRED}.
 */
public enum ImportRowCode {
  IMPORT_ROW_ZERO_AMOUNT(Kind.ROW_SKIPPED, "A zero-amount booking that changes no balance."),
  IMPORT_ROW_FILTERED(
      Kind.ROW_SKIPPED, "The template's row filter does not accept the row's filter column value."),
  IMPORT_ROW_CAMT_NOT_BOOKED(
      Kind.ROW_SKIPPED,
      "A CAMT entry that is not booked (pending, information only); it comes back booked later."),
  IMPORT_ROW_COLUMN_MISSING(
      Kind.ROW_ERROR, "The row has no cell at a mapped, required column (a short row)."),
  IMPORT_ROW_VALUE_MISSING(Kind.ROW_ERROR, "A required cell is empty."),
  IMPORT_ROW_DATE_UNPARSEABLE(Kind.ROW_ERROR, "Not a date in the template's date format."),
  IMPORT_ROW_AMOUNT_UNPARSEABLE(
      Kind.ROW_ERROR, "Not a number with the template's decimal and thousands separators."),
  IMPORT_ROW_AMOUNT_OUT_OF_RANGE(
      Kind.ROW_ERROR, "More than 16 digits before or 4 digits after the decimal separator."),
  IMPORT_ROW_ORIGINAL_AMOUNT_INCONSISTENT(
      Kind.ROW_ERROR, "The original amount and currency do not go together."),
  IMPORT_ROW_EXCHANGE_RATE_OUT_OF_RANGE(
      Kind.ROW_ERROR, "A bank exchange rate that is not positive, or out of range."),
  IMPORT_ROW_AMOUNT_BOTH_SIDES(Kind.ROW_ERROR, "Both the debit and the credit cell are filled."),
  IMPORT_ROW_CURRENCY_INVALID(Kind.ROW_ERROR, "Not an ISO 4217 currency code."),
  IMPORT_ROW_TYPE_NOT_ALLOWED(
      Kind.ROW_ERROR, "Names a transaction type this kind of template does not produce."),
  IMPORT_ROW_TYPE_AMBIGUOUS(
      Kind.ROW_ERROR, "The description patterns of more than one transaction type match."),
  IMPORT_ROW_LINE_UNMATCHED(
      Kind.ROW_ERROR, "A PDF booking line that the template's row pattern does not match."),
  IMPORT_ROW_BALANCE_MISMATCH(
      Kind.ROW_ERROR,
      "A PDF booking's running balance does not fit its amount; the amount was probably misread."),
  IMPORT_ROW_BALANCE_LINE_MISMATCH(
      Kind.ROW_ERROR,
      "A PDF balance line states another balance than the bookings above it lead to."),
  IMPORT_ROW_LINE_AMBIGUOUS(
      Kind.ROW_ERROR,
      "A PDF line that two of the template's patterns find; one of them is too broad."),
  IMPORT_ROW_LINE_BEFORE_SECTION(
      Kind.ROW_ERROR, "A PDF booking line before the first section the template finds."),
  IMPORT_ROW_CONTINUATION_TOO_LONG(
      Kind.ROW_ERROR, "More lines below a PDF booking would continue it than a booking may have."),
  IMPORT_ROW_MCC_INVALID(Kind.ROW_ERROR, "Not a four-digit ISO 18245 merchant category code."),
  IMPORT_ROW_EXTERNAL_ID_REPEATED(
      Kind.ROW_ERROR,
      "An earlier row of the same file carries the same bank reference; only the first is"
          + " imported."),
  IMPORT_ROW_CAMT_DETAILS_MISMATCH(
      Kind.ROW_ERROR, "A collective CAMT entry whose details do not add up to its amount."),
  IMPORT_ROW_CAMT_DETAILS_CURRENCY(
      Kind.ROW_ERROR, "A collective CAMT entry with a detail in another currency."),
  IMPORT_ROW_CAMT_VALUE_INVALID(
      Kind.ROW_ERROR, "A CAMT field holds a value its schema does not allow there."),
  IMPORT_ROW_FX_RATE_UNAVAILABLE(
      Kind.ROW_ERROR, "No exchange rate into the account currency for the booking date."),
  IMPORT_ROW_LEDGER_REJECTED(
      Kind.ROW_ERROR,
      "The ledger would refuse the row as a manual entry on this account; reason explains why."),
  IMPORT_ROW_FUTURE_DATE(Kind.ROW_WARNING, "The booking date lies after today."),
  IMPORT_ROW_CURRENCY_DIFFERS_FROM_ACCOUNT(
      Kind.ROW_WARNING, "In another currency than the account; converted at its date's rate."),
  IMPORT_ROW_CARD_SETTLEMENT_UNMATCHED(
      Kind.ROW_WARNING, "A card settlement with no matching payment from the paying account yet."),
  IMPORT_ROW_VALUE_DATE_ADJUSTED(
      Kind.ROW_WARNING, "A 30/360 value date such as 30.02 is read as February's last day."),
  IMPORT_ROW_SAVINGS_POCKET_CREATED(
      Kind.ROW_WARNING, "A savings pocket move; the commit creates the account's pocket."),
  IMPORT_ROW_SAVINGS_POCKET_UNAVAILABLE(
      Kind.ROW_WARNING,
      "A savings pocket move the pocket cannot take; imported as a one-sided transfer for review."),
  IMPORT_ROW_BEFORE_OPENING_BALANCE(
      Kind.ROW_WARNING, "The booking lies before the account's opening balance."),
  HOLDING_VALUE_UNREADABLE(
      Kind.HOLDINGS_LINE_ERROR, "A date, quantity, price, value or amount cannot be read."),
  HOLDING_CURRENCY_MISSING(
      Kind.HOLDINGS_LINE_ERROR, "The position names no currency, and no section above it does."),
  HOLDING_IDENTIFIER_INVALID(
      Kind.HOLDINGS_LINE_ERROR, "The ISIN's check digit is wrong, or the symbol is no ticker."),
  HOLDING_DUPLICATE(
      Kind.HOLDINGS_LINE_ERROR, "A second line of the same ISIN or symbol in one statement."),
  HOLDING_VALUE_MISMATCH(
      Kind.HOLDINGS_LINE_WARNING,
      "Quantity times price is not the value (nor a hundredth of it); check the columns.");

  /** The prefix of each code's message key in {@code messages*.properties}. */
  public static final String MESSAGE_KEY_PREFIX = "tmw.import.row.";

  /**
   * The names of row codes that were removed; never used again. Like {@link ProblemCode#RETIRED}:
   * removing a code is a declared breaking change, and this list only grows.
   */
  public static final List<String> RETIRED = List.of();

  /** Where a code appears and what it means for the row. */
  public enum Kind {
    ROW_ERROR("Row error: the row is not imported."),
    ROW_SKIPPED("Skipped: the row is shown, but never imported."),
    ROW_WARNING("Warning: the row stays included."),
    HOLDINGS_LINE_ERROR("Holdings line error: the line is not read."),
    HOLDINGS_LINE_WARNING("Holdings warning: shown, never blocking.");

    private final String sentence;

    Kind(String description) {
      this.sentence = description;
    }

    public String description() {
      return sentence;
    }
  }

  private final Kind rowKind;
  private final String oneLineMeaning;

  ImportRowCode(Kind kind, String meaning) {
    this.rowKind = kind;
    this.oneLineMeaning = meaning;
  }

  public Kind kind() {
    return rowKind;
  }

  /** One line for the catalogue. */
  public String meaning() {
    return oneLineMeaning;
  }

  /** The key of the code's EN/DE text, e.g. {@code tmw.import.row.IMPORT_ROW_ZERO_AMOUNT}. */
  public String messageKey() {
    return MESSAGE_KEY_PREFIX + name();
  }

  /** Whether the code is marked {@link Deprecated}: still written, but on its way out. */
  public boolean isDeprecated() {
    return Codes.isDeprecated(this);
  }
}
