package com.trackmywealth.backend.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * One row of an import batch's preview (US-07-04). {@code status} is {@code PARSED} (a new row),
 * {@code DUPLICATE} (the ledger holds it already: {@code duplicateOfTransactionId}), {@code ERROR}
 * ({@code error}) or {@code SKIPPED} (readable but never imported, its reason in {@code error},
 * US-07-19). {@code included} says whether the commit inserts it; {@code resultingTransactionId} is
 * the transaction it became once committed. {@code canonical} is {@code null} for a row that could
 * not be read; see {@link ParsedImportRow} for {@code rowNumber} and {@code rawData}.
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
                "Why the row is not imported: for an ERROR row the error, for a SKIPPED row the"
                    + " reason it is skipped (e.g. IMPORT_ROW_ZERO_AMOUNT). Null otherwise.")
        ImportRowErrorResponse error,
    List<ImportRowErrorResponse> warnings,
    UUID resultingTransactionId) {

  public ImportRowResponse {
    rawData = Collections.unmodifiableMap(new LinkedHashMap<>(rawData));
    warnings = List.copyOf(warnings);
  }
}
