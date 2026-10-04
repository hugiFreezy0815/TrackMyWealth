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

  /** {@code column}: the row has no cell at a mapped, required column (a short row). */
  public static final String COLUMN_MISSING = "IMPORT_ROW_COLUMN_MISSING";

  /** {@code column}: a required cell is empty. */
  public static final String VALUE_MISSING = "IMPORT_ROW_VALUE_MISSING";

  /** {@code column, value, pattern}: not a date in the template's date format. */
  public static final String DATE_UNPARSEABLE = "IMPORT_ROW_DATE_UNPARSEABLE";

  /** {@code column, value}: not a number with the template's decimal and thousands separators. */
  public static final String AMOUNT_UNPARSEABLE = "IMPORT_ROW_AMOUNT_UNPARSEABLE";

  /** {@code debitColumn, creditColumn}: both the debit and the credit cell are filled. */
  public static final String AMOUNT_BOTH_SIDES = "IMPORT_ROW_AMOUNT_BOTH_SIDES";

  /** {@code column, value}: not an ISO 4217 currency code. */
  public static final String CURRENCY_INVALID = "IMPORT_ROW_CURRENCY_INVALID";

  /**
   * {@code column, value}: names a transaction type the template class does not produce (e.g.
   * {@code BUY} in a cash transactions file).
   */
  public static final String TYPE_NOT_ALLOWED = "IMPORT_ROW_TYPE_NOT_ALLOWED";

  /** {@code column, value}: not a four-digit ISO 18245 merchant category code. */
  public static final String MCC_INVALID = "IMPORT_ROW_MCC_INVALID";
}
