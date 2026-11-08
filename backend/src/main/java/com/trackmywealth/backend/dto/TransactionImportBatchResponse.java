package com.trackmywealth.backend.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * US-07-37: provenance of a transaction imported from a statement, without exposing raw source
 * data. {@code fileFormat} is the public import vocabulary: both PDF template kinds (text and OCR)
 * are reported as {@code PDF}, and a template-less statement document as {@code CAMT} (#350).
 */
public record TransactionImportBatchResponse(
    UUID id,
    @Schema(
            description = "Statement format the transaction was imported from.",
            allowableValues = {FORMAT_CSV, FORMAT_SPREADSHEET, FORMAT_PDF, FORMAT_CAMT})
        String fileFormat,
    OffsetDateTime uploadedAt) {

  public static final String FORMAT_CSV = "CSV";
  public static final String FORMAT_SPREADSHEET = "SPREADSHEET";
  public static final String FORMAT_PDF = "PDF";
  public static final String FORMAT_CAMT = "CAMT";
}
