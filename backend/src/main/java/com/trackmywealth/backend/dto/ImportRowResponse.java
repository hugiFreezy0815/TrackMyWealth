package com.trackmywealth.backend.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * One row of an import batch's preview (US-07-04). {@code status} is {@code PARSED} (a new row),
 * {@code DUPLICATE} (the ledger holds it already: {@code duplicateOfTransactionId}, shown as {@code
 * duplicateOf}), {@code ERROR} ({@code error}) or {@code SKIPPED} (readable but never imported, its
 * reason in {@code error}, US-07-19). {@code included} says whether the commit inserts it; {@code
 * resultingTransactionId} is the transaction it became once committed. {@code canonical} is {@code
 * null} for a row that could not be read; see {@link ParsedImportRow} for {@code rowNumber} and
 * {@code rawData}. While the batch is {@code PARSED}, {@code status}, {@code included} and the
 * duplicate are what its commit would do now; {@code recheck} says why that differs from the parse
 * (#308).
 */
public record ImportRowResponse(
    int rowNumber,
    String status,
    boolean included,
    Map<String, String> rawData,
    CanonicalImportRow canonical,
    UUID duplicateOfTransactionId,
    @Schema(
            description =
                "For a row with a duplicateOfTransactionId, that ledger row: its booking date, amount,"
                    + " currency and description. The duplicate rule compares booking date, amount"
                    + " and currency only (US-07-17), so the descriptions may differ; the member"
                    + " can force the row in. Null exactly when duplicateOfTransactionId is.")
        ImportDuplicateOfResponse duplicateOf,
    @Schema(
            description =
                "Why the row is not imported: for an ERROR row the error, for a SKIPPED row the"
                    + " reason it is skipped (e.g. IMPORT_ROW_ZERO_AMOUNT). Null otherwise.")
        ImportRowErrorResponse error,
    List<ImportRowErrorResponse> warnings,
    UUID resultingTransactionId,
    @Schema(
            description =
                "Set while the batch is PARSED for a row its commit would now treat otherwise than"
                    + " the preview stored it (#308), as the ledger has changed since: NEW, a"
                    + " duplicate whose booking is gone, imported as a new row; REPOINTED, a"
                    + " duplicate whose booking another ledger row holds now (a correction, a"
                    + " restored void); DUPLICATE, a new row whose booking the ledger has gained."
                    + " status, included, duplicateOfTransactionId and duplicateOf already show"
                    + " what the commit will do. Null for every other row.",
            allowableValues = {"NEW", "REPOINTED", "DUPLICATE"},
            nullable = true)
        String recheck) {

  public ImportRowResponse {
    rawData = Collections.unmodifiableMap(new LinkedHashMap<>(rawData));
    warnings = List.copyOf(warnings);
  }
}
