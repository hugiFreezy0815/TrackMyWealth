package com.trackmywealth.backend.dto;

import java.util.Set;

/**
 * The values of {@code import_batch} and {@code import_row_raw} that US-07-04 writes (V15, V67).
 *
 * <p>A batch is {@link #UPLOADED} until its rows are parsed with a template, {@link #PARSED} while
 * the member reviews them (a re-parse replaces them), and {@link #COMMITTED} once its included rows
 * are in the ledger. {@link #DISCARDED} ends it before the commit and deletes its file. A rollback
 * (US-07-05) ends a committed batch {@link #ROLLED_BACK} (its transactions deleted) or {@link
 * #VOIDED} (its transactions voided).
 */
public final class ImportBatchValues {

  public static final String UPLOADED = "UPLOADED";
  public static final String PARSED = "PARSED";
  public static final String COMMITTED = "COMMITTED";
  public static final String DISCARDED = "DISCARDED";

  /** US-07-05: rolled back while unmodified; its transactions were deleted. */
  public static final String ROLLED_BACK = "ROLLED_BACK";

  /** US-07-05: rolled back once modified; its transactions were voided. */
  public static final String VOIDED = "VOIDED";

  /** The statuses a batch can still be parsed in. */
  public static final Set<String> PARSEABLE = Set.of(UPLOADED, PARSED);

  /** {@code source_kind} and so {@code transaction.source}: a delimited text file. */
  public static final String SOURCE_CSV = "CSV";

  /** {@code source_kind} of a PDF statement (#268) and of a CAMT XML statement (#350). */
  public static final String SOURCE_DOCUMENT = "DOCUMENT";

  /**
   * #350: what every external id of a CAMT row starts with. A CAMT statement and a PDF template of
   * one account share the source {@code DOCUMENT}, but not their references: a row is compared with
   * a ledger row of the other scheme by booking date and amount (duplicate rule 2), however the two
   * references differ.
   */
  public static final String CAMT_REFERENCE_PREFIX = "CAMT:";

  /** {@code source_file_storage_ref} of a file kept in {@code import_file}. */
  public static final String STORAGE_DATABASE = "db";

  /** A row the commit would insert: new, or a duplicate the member forced in. */
  public static final String ROW_PARSED = ImportRowErrorValues.STATUS_PARSED;

  /** A row the ledger already holds (US-07-04 duplicate rules); excluded unless forced in. */
  public static final String ROW_DUPLICATE = "DUPLICATE";

  public static final String ROW_ERROR = ImportRowErrorValues.STATUS_ERROR;

  /**
   * A readable row that is shown but never included or exported as an error; its reason is in
   * {@code error_code} (so far only {@code IMPORT_ROW_ZERO_AMOUNT}, US-07-19).
   */
  public static final String ROW_SKIPPED = ImportRowErrorValues.STATUS_SKIPPED;

  /** Every row status, for the preview's filter. */
  public static final Set<String> ROW_STATUSES =
      Set.of(ROW_PARSED, ROW_DUPLICATE, ROW_ERROR, ROW_SKIPPED);

  /** The row statuses a commit checks against the ledger again, its readable rows (#287). */
  public static final Set<String> ROW_RECHECKED = Set.of(ROW_PARSED, ROW_DUPLICATE);

  /**
   * Re-check {@code recheck} (#308): a duplicate whose booking the ledger lost since the preview;
   * the commit imports it as a new, included row.
   */
  public static final String RECHECK_NEW = "NEW";

  /**
   * Re-check {@code recheck} (#308): a duplicate whose booking the ledger holds as another row now
   * (a correction, a restored void); it stays a duplicate, of that row.
   */
  public static final String RECHECK_REPOINTED = "REPOINTED";

  /**
   * Re-check {@code recheck} (#308): a new, included row whose booking the ledger has gained since
   * the preview; the commit leaves it out as a duplicate.
   */
  public static final String RECHECK_DUPLICATE = "DUPLICATE";

  /** Warning {@code date}: the booking date lies after today. The row stays included. */
  public static final String WARNING_FUTURE_DATE = "IMPORT_ROW_FUTURE_DATE";

  /**
   * Warning {@code currency, accountCurrency}: the row is in another currency than the account and
   * is converted at its booking date's rate (PR-011). The row stays included.
   */
  public static final String WARNING_CURRENCY_DIFFERS = "IMPORT_ROW_CURRENCY_DIFFERS_FROM_ACCOUNT";

  /** A positive card-side settlement has no matching source-account payment yet. */
  public static final String WARNING_CARD_SETTLEMENT_UNMATCHED =
      "IMPORT_ROW_CARD_SETTLEMENT_UNMATCHED";

  /**
   * Warning {@code date} (#334): the value date was written as a February day that does not exist
   * (the 30/360 interest convention) and is read as {@code date}, that February's last day. The row
   * stays included.
   */
  public static final String WARNING_VALUE_DATE_ADJUSTED = "IMPORT_ROW_VALUE_DATE_ADJUSTED";

  /**
   * US-07-22 (#325): the row is a savings pocket move and the account has no pocket yet; the commit
   * creates it and books the move's other leg there. The row stays included.
   */
  public static final String WARNING_SAVINGS_POCKET_CREATED = "IMPORT_ROW_SAVINGS_POCKET_CREATED";

  /**
   * US-07-22: the row is a savings pocket move, but the account's pocket cannot take it (archived,
   * another currency, not editable by the member), the account has none and only an owner may have
   * it created, or the account can have none; it is imported as a one-sided transfer, pending
   * review. The row stays included.
   */
  public static final String WARNING_SAVINGS_POCKET_UNAVAILABLE =
      "IMPORT_ROW_SAVINGS_POCKET_UNAVAILABLE";

  /** Booking is strictly before the account opening balance; importing remains allowed. */
  public static final String WARNING_BEFORE_OPENING_BALANCE = "IMPORT_ROW_BEFORE_OPENING_BALANCE";

  private ImportBatchValues() {}

  // --- US-07-10: what the file says about its account (import_batch.account_identification) ---

  /** The file names the batch's account. */
  public static final String IDENTIFIED_MATCHED = "MATCHED";

  /** The file's masked card number fits the batch's account; masked digits always need a yes. */
  public static final String IDENTIFIED_MATCHED_MASKED = "MATCHED_MASKED";

  /** The file names another account the member may import into: {@code proposedAccountId}. */
  public static final String IDENTIFIED_OTHER_ACCOUNT = "OTHER_ACCOUNT";

  /** The file names an account that is not registered, or not one the member may import into. */
  public static final String IDENTIFIED_NO_MATCH = "NO_MATCH";

  /** The file names no identifier the template's rule can read, or several different ones. */
  public static final String IDENTIFIED_NOT_FOUND = "NOT_FOUND";

  /** Account identification is not configured on this server. */
  public static final String IDENTIFIED_UNAVAILABLE = "UNAVAILABLE";

  // --- US-07-12: the statement's balance (import_batch.statement_balance_outcome) ---

  /** The file states one closing balance in the account's currency. */
  public static final String BALANCE_FOUND = "FOUND";

  /** No line of the file states a balance in the account's currency. */
  public static final String BALANCE_NOT_FOUND = "NOT_FOUND";

  /** A line matched the template's rule, but its date, amount or currency could not be read. */
  public static final String BALANCE_UNREADABLE = "UNREADABLE";

  /** The file states different balances for its latest date. */
  public static final String BALANCE_AMBIGUOUS = "AMBIGUOUS";

  /**
   * The closing balance was read, but its date lies in the future, before the account was opened or
   * after it was closed: the commit imports the rows and records no snapshot.
   */
  public static final String BALANCE_OUT_OF_RANGE = "OUT_OF_RANGE";

  // --- US-07-12: what became of the balance (import_batch.statement_balance_snapshot) ---

  /** Before the commit: no imported snapshot of that date exists, so the commit records one. */
  public static final String SNAPSHOT_NEW = "NEW";

  /** The commit recorded the balance as a {@code DOCUMENT} snapshot. */
  public static final String SNAPSHOT_RECORDED = "RECORDED";

  /** An imported snapshot of that date already states the same balance: none is recorded. */
  public static final String SNAPSHOT_ALREADY_RECORDED = "ALREADY_RECORDED";

  /** An imported snapshot of that date states another balance: none is recorded. */
  public static final String SNAPSHOT_CONFLICTING = "CONFLICTING";

  /** A rollback deleted the recorded snapshot. */
  public static final String SNAPSHOT_DELETED = "DELETED";

  /**
   * A rollback withdrew the recorded snapshot: an accepted reconciliation's adjusting entry still
   * points at it, so it stays as history but no longer counts.
   */
  public static final String SNAPSHOT_WITHDRAWN = "WITHDRAWN";
}
