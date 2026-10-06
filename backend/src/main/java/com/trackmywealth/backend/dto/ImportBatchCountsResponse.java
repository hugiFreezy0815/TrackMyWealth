package com.trackmywealth.backend.dto;

/**
 * How the rows of an import batch stand (US-07-04): {@code newRows} are {@code PARSED} rows the
 * ledger does not hold yet, {@code warnings} the rows with at least one warning, {@code included}
 * the rows a commit inserts (or inserted: then {@code imported} equals it).
 */
public record ImportBatchCountsResponse(
    int total, int newRows, int duplicates, int errors, int warnings, int included, int imported) {

  public static final ImportBatchCountsResponse NONE =
      new ImportBatchCountsResponse(0, 0, 0, 0, 0, 0, 0);
}
