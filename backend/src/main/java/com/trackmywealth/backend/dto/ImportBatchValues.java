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

  /** {@code source_kind} of a PDF statement (#268). */
  public static final String SOURCE_DOCUMENT = "DOCUMENT";

  /** {@code source_file_storage_ref} of a file kept in {@code import_file}. */
  public static final String STORAGE_DATABASE = "db";

  /** A row the commit would insert: new, or a duplicate the member forced in. */
  public static final String ROW_PARSED = ImportRowErrorValues.STATUS_PARSED;

  /** A row the ledger already holds (US-07-04 duplicate rules); excluded unless forced in. */
  public static final String ROW_DUPLICATE = "DUPLICATE";

  public static final String ROW_ERROR = ImportRowErrorValues.STATUS_ERROR;

  /** Every row status, for the preview's filter. */
  public static final Set<String> ROW_STATUSES = Set.of(ROW_PARSED, ROW_DUPLICATE, ROW_ERROR);

  /** Warning {@code date}: the booking date lies after today. The row stays included. */
  public static final String WARNING_FUTURE_DATE = "IMPORT_ROW_FUTURE_DATE";

  /**
   * Warning {@code currency, accountCurrency}: the row is in another currency than the account and
   * is converted at its booking date's rate (PR-011). The row stays included.
   */
  public static final String WARNING_CURRENCY_DIFFERS = "IMPORT_ROW_CURRENCY_DIFFERS_FROM_ACCOUNT";

  private ImportBatchValues() {}
}
