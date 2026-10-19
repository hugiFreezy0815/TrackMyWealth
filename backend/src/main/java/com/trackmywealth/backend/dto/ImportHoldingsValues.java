package com.trackmywealth.backend.dto;

/**
 * The values of a holdings statement's batch and lines (US-07-13, #273; V99's {@code
 * import_holding_row}, {@code import_batch.holdings_*}).
 */
public final class ImportHoldingsValues {

  /** {@code import_holding_row.kind}: a position, read into a holding at the commit. */
  public static final String KIND_POSITION = "POSITION";

  /** {@code import_holding_row.kind}: a cash balance in one currency. */
  public static final String KIND_CASH = "CASH";

  /** {@code import_holding_row.kind}: a line taken for a position or cash, but unreadable. */
  public static final String KIND_ERROR = "ERROR";

  /** {@code holdings_date_source}: the date the statement states. */
  public static final String DATE_STATEMENT = "STATEMENT";

  /** {@code holdings_date_source}: the date the member entered at upload. */
  public static final String DATE_MEMBER = "MEMBER";

  /** Preview: the commit would record a new snapshot. */
  public static final String SNAPSHOT_NEW = "NEW";

  /** Preview: an imported snapshot of the date is in effect; the commit is refused. */
  public static final String SNAPSHOT_EXISTS = "EXISTS";

  /** Preview: the account's dates do not admit the snapshot's (future, before opened, closed). */
  public static final String SNAPSHOT_OUT_OF_RANGE = "OUT_OF_RANGE";

  /** After the commit; rollback outcomes as for a statement balance (US-07-12). */
  public static final String SNAPSHOT_RECORDED = ImportBatchValues.SNAPSHOT_RECORDED;

  public static final String SNAPSHOT_DELETED = ImportBatchValues.SNAPSHOT_DELETED;
  public static final String SNAPSHOT_WITHDRAWN = ImportBatchValues.SNAPSHOT_WITHDRAWN;

  /** A position's security: found by its ISIN, or by a ticker the workspace mapped. */
  public static final String RESOLVED = "RESOLVED";

  /** A position no security matches yet: create it (with its ticker) before the commit. */
  public static final String UNRESOLVED = "UNRESOLVED";

  /** Line error: the date, quantity, price, value or amount cannot be read. */
  public static final String ERROR_VALUE_UNREADABLE = "HOLDING_VALUE_UNREADABLE";

  /** Line error: a position's currency is no currency code, and no section names one. */
  public static final String ERROR_CURRENCY_MISSING = "HOLDING_CURRENCY_MISSING";

  /** Line error: the ISIN's check digit is wrong, or the symbol is no ticker. */
  public static final String ERROR_IDENTIFIER_INVALID = "HOLDING_IDENTIFIER_INVALID";

  /** Line error: a second line of the same ISIN or symbol; the statement lists each once. */
  public static final String ERROR_DUPLICATE = "HOLDING_DUPLICATE";

  /**
   * Warning: quantity times price is not the value, nor a hundredth of it (a bond quoted in
   * percent) - shown, never blocking: the statement may round, but the columns may also be read
   * wrong.
   */
  public static final String WARNING_VALUE_MISMATCH = "HOLDING_VALUE_MISMATCH";

  private ImportHoldingsValues() {}
}
