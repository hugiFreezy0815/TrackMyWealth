package com.trackmywealth.backend.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Which source column feeds which canonical field (US-07-03, FR-IMP-021), stored as {@code
 * import_template.column_mapping}. Each value is a header cell's text, or a 0-based column index
 * written as digits: always for a file without a header row, and for a header whose names repeat
 * (mapping such a name would be ambiguous). A field left {@code null} is not imported.
 *
 * <p>Which fields are required depends on the template: {@code bookingDate} always, {@code amount}
 * or {@code debitAmount} plus {@code creditAmount} by amount representation, {@code currency} for
 * currency mode {@code PER_ROW}.
 */
@Schema(
    description =
        "Source column per canonical field: a header cell's text, or a 0-based column index"
            + " written as digits (files without a header row, or repeated header names).")
public record ImportColumnMapping(
    @Schema(description = "Required.") String bookingDate,
    String valueDate,
    @Schema(description = "Required for SINGLE_SIGNED_COLUMN and NEGATIVE_IN_PARENTHESES.")
        String amount,
    @Schema(description = "Required for SEPARATE_DEBIT_CREDIT.") String debitAmount,
    @Schema(description = "Required for SEPARATE_DEBIT_CREDIT.") String creditAmount,
    @Schema(description = "Required for currency mode PER_ROW.") String currency,
    String description,
    String counterpartyName,
    String externalId,
    String transactionType,
    String mcc,
    String iso20022BankTransactionCode,
    String notes) {

  /** The mapped fields by canonical name, in declaration order; unmapped fields are left out. */
  public Map<String, String> mappedColumns() {
    Map<String, String> columns = new LinkedHashMap<>();
    put(columns, "bookingDate", bookingDate);
    put(columns, "valueDate", valueDate);
    put(columns, "amount", amount);
    put(columns, "debitAmount", debitAmount);
    put(columns, "creditAmount", creditAmount);
    put(columns, "currency", currency);
    put(columns, "description", description);
    put(columns, "counterpartyName", counterpartyName);
    put(columns, "externalId", externalId);
    put(columns, "transactionType", transactionType);
    put(columns, "mcc", mcc);
    put(columns, "iso20022BankTransactionCode", iso20022BankTransactionCode);
    put(columns, "notes", notes);
    return columns;
  }

  private static void put(Map<String, String> columns, String field, String column) {
    if (column != null && !column.isBlank()) {
      columns.put(field, column);
    }
  }
}
