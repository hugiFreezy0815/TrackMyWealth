package com.trackmywealth.backend.dto;

/**
 * Stable row statuses and row error codes of a parsed import file (US-07-03). The codes are API
 * contract and are never renamed. Each one's arguments are listed in the order they are passed to
 * the message ({@code tmw.import.row.<code>} in {@code messages.properties}): {@code column} is the
 * source column as the template maps it, {@code value} the rejected cell.
 */
public final class ImportRowErrorValues {

  private ImportRowErrorValues() {}

  public static final String STATUS_PARSED = "PARSED";
  public static final String STATUS_ERROR = "ERROR";
  public static final String STATUS_SKIPPED = "SKIPPED";

  /** A zero-amount booking that changes no balance (US-07-19). */
  public static final String ZERO_AMOUNT = "IMPORT_ROW_ZERO_AMOUNT";

  /** The template's row filter does not accept the row's value in its filter column (US-07-09). */
  public static final String ROW_FILTERED = "IMPORT_ROW_FILTERED";

  /** {@code column}: the row has no cell at a mapped, required column (a short row). */
  public static final String COLUMN_MISSING = "IMPORT_ROW_COLUMN_MISSING";

  /** {@code column}: a required cell is empty. */
  public static final String VALUE_MISSING = "IMPORT_ROW_VALUE_MISSING";

  /** {@code column, value, pattern}: not a date in the template's date format. */
  public static final String DATE_UNPARSEABLE = "IMPORT_ROW_DATE_UNPARSEABLE";

  /** {@code column, value}: not a number with the template's decimal and thousands separators. */
  public static final String AMOUNT_UNPARSEABLE = "IMPORT_ROW_AMOUNT_UNPARSEABLE";

  /**
   * {@code column, value}: a number, but outside {@code NUMERIC(20,4)} - more than 16 digits before
   * or 4 (non-zero) digits after the decimal separator.
   */
  public static final String AMOUNT_OUT_OF_RANGE = "IMPORT_ROW_AMOUNT_OUT_OF_RANGE";

  /**
   * {@code column, currencyColumn}: the original amount and currency (US-07-18) do not go together:
   * only one of the two cells is filled, or only one of the two amounts is zero. The original
   * amount takes the amount's sign (US-07-24), so its own sign is never an inconsistency.
   */
  public static final String ORIGINAL_AMOUNT_INCONSISTENT =
      "IMPORT_ROW_ORIGINAL_AMOUNT_INCONSISTENT";

  /**
   * {@code column, value}: a bank exchange rate that is not greater than 0, or outside {@code
   * NUMERIC(20,10)} - more than 10 digits before or after the decimal separator (US-07-18).
   */
  public static final String EXCHANGE_RATE_OUT_OF_RANGE = "IMPORT_ROW_EXCHANGE_RATE_OUT_OF_RANGE";

  /** {@code debitColumn, creditColumn}: both the debit and the credit cell are filled. */
  public static final String AMOUNT_BOTH_SIDES = "IMPORT_ROW_AMOUNT_BOTH_SIDES";

  /** {@code column, value}: not an ISO 4217 currency code. */
  public static final String CURRENCY_INVALID = "IMPORT_ROW_CURRENCY_INVALID";

  /**
   * {@code column, value}: names a transaction type the template class does not produce (e.g.
   * {@code BUY} in a cash transactions file).
   */
  public static final String TYPE_NOT_ALLOWED = "IMPORT_ROW_TYPE_NOT_ALLOWED";

  /**
   * {@code column}: the description patterns of more than one transaction type match the row's
   * description (US-07-18); the template decides, never the order of its patterns.
   */
  public static final String TYPE_AMBIGUOUS = "IMPORT_ROW_TYPE_AMBIGUOUS";

  /**
   * {@code value}: a PDF booking line (one the template's record-start pattern finds) that its row
   * pattern does not match, so it cannot be cut into the layout's columns (#268).
   */
  public static final String LINE_UNMATCHED = "IMPORT_ROW_LINE_UNMATCHED";

  /**
   * {@code value, expected}: a PDF booking's running balance is not the balance before it plus its
   * amount, so its amount or sign was misread - e.g. read from the wrong column (#268).
   */
  public static final String BALANCE_MISMATCH = "IMPORT_ROW_BALANCE_MISMATCH";

  /**
   * {@code value, expected}: a PDF balance line states another balance than the bookings above it
   * lead to, so a booking between them was not read as one (PR #281 review). The row is the balance
   * line itself, its raw data the line; the bookings around it keep their own status.
   */
  public static final String BALANCE_LINE_MISMATCH = "IMPORT_ROW_BALANCE_LINE_MISMATCH";

  /**
   * {@code value, pattern}: a PDF line that the record-start pattern finds, but another pattern of
   * the layout too (a section's start, a header line of the header labels, or a balance line
   * without a balance column to check it), so it was read as that pattern's line and not imported.
   * One of the two is too broad (#268).
   */
  public static final String LINE_AMBIGUOUS = "IMPORT_ROW_LINE_AMBIGUOUS";

  /**
   * {@code value}: a PDF line that the record-start pattern finds before the first line the
   * layout's section pattern finds, where no booking is read, so it was not imported. The section
   * pattern may miss the first section's marker (PR #281 review).
   */
  public static final String LINE_BEFORE_SECTION = "IMPORT_ROW_LINE_BEFORE_SECTION";

  /**
   * {@code value, max}: more lines below a PDF booking would continue it than a booking may have
   * ({@link ImportPdfLayout#MAX_CONTINUATION_LINES}) - likely the statement's own text, which the
   * layout's continuation end pattern should end (PR #281 review).
   */
  public static final String CONTINUATION_TOO_LONG = "IMPORT_ROW_CONTINUATION_TOO_LONG";

  /** {@code column, value}: not a four-digit ISO 18245 merchant category code. */
  public static final String MCC_INVALID = "IMPORT_ROW_MCC_INVALID";

  /**
   * {@code externalId, firstRow}: an earlier row of the same file carries the same bank reference
   * (US-07-04); one reference is one booking, so only the first row is imported.
   */
  public static final String EXTERNAL_ID_REPEATED = "IMPORT_ROW_EXTERNAL_ID_REPEATED";

  /**
   * {@code currency, accountCurrency, date}: the row is in another currency than the account and no
   * exchange rate for its booking date is available, not even from the provider (US-07-04, #223).
   */
  public static final String FX_RATE_UNAVAILABLE = "IMPORT_ROW_FX_RATE_UNAVAILABLE";

  /**
   * {@code reason}: the ledger would refuse the row as a manual entry on this account (e.g. a type
   * the account does not take, or an amount whose sign does not fit its type); {@code reason} is
   * the ledger's own explanation, in English until EPIC-21 (US-07-04).
   */
  public static final String LEDGER_REJECTED = "IMPORT_ROW_LEDGER_REJECTED";
}
