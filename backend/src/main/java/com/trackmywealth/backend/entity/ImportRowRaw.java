package com.trackmywealth.backend.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Maps {@code import_row_raw} (V15, V67): one data row of an import batch as previewed (US-07-04).
 * Its raw cells are kept whatever its status, so a corrected template can re-parse it and an error
 * row can be exported (FR-IMP-012/025). Rows are inserted and linked to their transactions in bulk
 * by {@code ImportRowRawBatchRepository}; JPA reads them and changes {@code included}.
 *
 * <p>{@code rawData} and {@code errorArgs} are JSON (kept in their order), {@code canonicalData}
 * JSONB; all three are held as their JSON text, which the services read and write as typed values.
 */
@Entity
@Table(name = "import_row_raw")
public class ImportRowRaw {

  private static final String JSONB = "jsonb";
  // V67: kept as written, so the cells and message arguments keep their order.
  private static final String JSON = "json";

  @Id
  @Column(columnDefinition = "uuid")
  private UUID id;

  @Column(name = "import_batch_id", nullable = false, updatable = false)
  private UUID importBatchId;

  @Column(name = "workspace_id", nullable = false, updatable = false)
  private UUID workspaceId;

  @Column(name = "row_number", nullable = false, updatable = false)
  private int rowNumber;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "raw_data", columnDefinition = JSON, nullable = false, updatable = false)
  private String rawData;

  @Column(name = "parse_status", nullable = false, updatable = false)
  private String parseStatus;

  @Column(nullable = false)
  private boolean included;

  @Column(name = "duplicate_of_transaction_id", updatable = false)
  private UUID duplicateOfTransactionId;

  @JdbcTypeCode(SqlTypes.ARRAY)
  @Column(name = "warning_codes", columnDefinition = "text[]", nullable = false, updatable = false)
  private String[] warningCodes = new String[0];

  @Column(name = "error_code", updatable = false)
  private String errorCode;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "error_args", columnDefinition = JSON, updatable = false)
  private String errorArgs;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "canonical_data", columnDefinition = JSONB, updatable = false)
  private String canonicalData;

  @Column(name = "booking_date", updatable = false)
  private LocalDate bookingDate;

  @Column(precision = 20, scale = 4, updatable = false)
  private BigDecimal amount;

  @JdbcTypeCode(SqlTypes.CHAR)
  @Column(length = 3, updatable = false)
  private String currency;

  @Column(name = "resulting_transaction_id", updatable = false)
  private UUID resultingTransactionId;

  public UUID getId() {
    return id;
  }

  public UUID getImportBatchId() {
    return importBatchId;
  }

  public UUID getWorkspaceId() {
    return workspaceId;
  }

  public int getRowNumber() {
    return rowNumber;
  }

  public String getRawData() {
    return rawData;
  }

  public String getParseStatus() {
    return parseStatus;
  }

  public boolean isIncluded() {
    return included;
  }

  public void setIncluded(boolean included) {
    this.included = included;
  }

  public UUID getDuplicateOfTransactionId() {
    return duplicateOfTransactionId;
  }

  public String[] getWarningCodes() {
    return Arrays.copyOf(warningCodes, warningCodes.length);
  }

  public String getErrorCode() {
    return errorCode;
  }

  public String getErrorArgs() {
    return errorArgs;
  }

  public String getCanonicalData() {
    return canonicalData;
  }

  public LocalDate getBookingDate() {
    return bookingDate;
  }

  public BigDecimal getAmount() {
    return amount;
  }

  public String getCurrency() {
    return currency;
  }

  public UUID getResultingTransactionId() {
    return resultingTransactionId;
  }
}
