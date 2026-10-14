package com.trackmywealth.backend.dto;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * The rows of a {@code PARSED} preview whose stored state is out of date, by row id, each with what
 * a commit would do with it now (#287, #308). A row that is absent goes in as stored. Internal, not
 * part of any API response.
 */
public record ImportRecheck(Map<UUID, ImportRowRecheck> rows) {

  /** Nothing to change: a batch that is not {@code PARSED}, or a preview still up to date. */
  public static final ImportRecheck NONE = new ImportRecheck(Map.of());

  public ImportRecheck {
    rows = Collections.unmodifiableMap(new LinkedHashMap<>(rows));
  }

  /** The row's re-check, or {@code null} when it goes in as stored. */
  public ImportRowRecheck of(UUID rowId) {
    return rows.get(rowId);
  }

  public boolean isEmpty() {
    return rows.isEmpty();
  }

  /** How many more duplicates the batch has than its rows store (fewer when negative). */
  public int duplicateDelta() {
    int delta = 0;
    for (ImportRowRecheck row : rows.values()) {
      if (ImportBatchValues.RECHECK_NEW.equals(row.recheck())) {
        delta--;
      } else if (ImportBatchValues.RECHECK_DUPLICATE.equals(row.recheck())) {
        delta++;
      }
    }
    return delta;
  }

  /** {@code stored}, the counts of the rows as stored, as the commit would leave them. */
  public ImportBatchCountsResponse applyTo(ImportBatchCountsResponse stored) {
    int duplicates = duplicateDelta();
    int included = 0;
    for (ImportRowRecheck row : rows.values()) {
      included += Boolean.compare(row.included(), row.wasIncluded());
    }
    return new ImportBatchCountsResponse(
        stored.total(),
        stored.newRows() - duplicates,
        stored.duplicates() + duplicates,
        stored.errors(),
        stored.skipped(),
        stored.warnings(),
        stored.included() + included,
        stored.imported());
  }
}
