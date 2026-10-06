package com.trackmywealth.backend.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * One import row as the preview stores it (US-07-04), ready for {@code
 * ImportRowRawBatchRepository}'s bulk insert. The JSON fields are already serialized; {@code
 * bookingDate}, {@code amount} and {@code currency} repeat the canonical values a reconciliation
 * queries ({@code null} when the row could not be read).
 */
public record StagedImportRow(
    UUID id,
    int rowNumber,
    String rawDataJson,
    String status,
    boolean included,
    UUID duplicateOfTransactionId,
    List<String> warningCodes,
    String errorCode,
    String errorArgsJson,
    String canonicalJson,
    LocalDate bookingDate,
    BigDecimal amount,
    String currency) {

  public StagedImportRow {
    warningCodes = List.copyOf(warningCodes);
  }
}
