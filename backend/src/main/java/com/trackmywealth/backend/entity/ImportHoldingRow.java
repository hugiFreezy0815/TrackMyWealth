package com.trackmywealth.backend.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Maps {@code import_holding_row} (V99, #273): one line of a holdings statement as an upload read
 * it - a position, a cash balance, or an unreadable line. Written once per parse (a re-parse
 * replaces the batch's lines) and never changed: which security a position is is resolved again on
 * every read and at the commit, not stored. Under its own workspace policy, as raw bank data.
 */
@Entity
@Table(name = "import_holding_row")
public class ImportHoldingRow {

  private static final String UUID_COLUMN = "uuid";

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  @Column(columnDefinition = UUID_COLUMN)
  private UUID id;

  @Column(name = "workspace_id", columnDefinition = UUID_COLUMN, nullable = false)
  private UUID workspaceId;

  @Column(name = "import_batch_id", columnDefinition = UUID_COLUMN, nullable = false)
  private UUID importBatchId;

  @Column(name = "row_number", nullable = false)
  private int rowNumber;

  @Column(nullable = false)
  private String kind;

  @JdbcTypeCode(SqlTypes.CHAR)
  @Column(length = 12)
  private String isin;

  private String symbol;

  @Column(name = "security_name")
  private String securityName;

  private BigDecimal quantity;

  @Column(name = "reported_price")
  private BigDecimal reportedPrice;

  @Column(name = "reported_value")
  private BigDecimal reportedValue;

  @JdbcTypeCode(SqlTypes.CHAR)
  @Column(length = 3)
  private String currency;

  private BigDecimal amount;

  @Column(name = "raw_text")
  private String rawText;

  @Column(name = "error_code")
  private String errorCode;

  @JdbcTypeCode(SqlTypes.ARRAY)
  @Column(name = "warning_codes", columnDefinition = "text[]", nullable = false)
  private String[] warningCodes = new String[0];

  public UUID getId() {
    return id;
  }

  public UUID getWorkspaceId() {
    return workspaceId;
  }

  public void setWorkspaceId(UUID workspaceId) {
    this.workspaceId = workspaceId;
  }

  public UUID getImportBatchId() {
    return importBatchId;
  }

  public void setImportBatchId(UUID importBatchId) {
    this.importBatchId = importBatchId;
  }

  public int getRowNumber() {
    return rowNumber;
  }

  public void setRowNumber(int rowNumber) {
    this.rowNumber = rowNumber;
  }

  public String getKind() {
    return kind;
  }

  public void setKind(String kind) {
    this.kind = kind;
  }

  public String getIsin() {
    return isin;
  }

  public void setIsin(String isin) {
    this.isin = isin;
  }

  public String getSymbol() {
    return symbol;
  }

  public void setSymbol(String symbol) {
    this.symbol = symbol;
  }

  public String getSecurityName() {
    return securityName;
  }

  public void setSecurityName(String securityName) {
    this.securityName = securityName;
  }

  public BigDecimal getQuantity() {
    return quantity;
  }

  public void setQuantity(BigDecimal quantity) {
    this.quantity = quantity;
  }

  public BigDecimal getReportedPrice() {
    return reportedPrice;
  }

  public void setReportedPrice(BigDecimal reportedPrice) {
    this.reportedPrice = reportedPrice;
  }

  public BigDecimal getReportedValue() {
    return reportedValue;
  }

  public void setReportedValue(BigDecimal reportedValue) {
    this.reportedValue = reportedValue;
  }

  public String getCurrency() {
    return currency;
  }

  public void setCurrency(String currency) {
    this.currency = currency;
  }

  public BigDecimal getAmount() {
    return amount;
  }

  public void setAmount(BigDecimal amount) {
    this.amount = amount;
  }

  public String getRawText() {
    return rawText;
  }

  public void setRawText(String rawText) {
    this.rawText = rawText;
  }

  public String getErrorCode() {
    return errorCode;
  }

  public void setErrorCode(String errorCode) {
    this.errorCode = errorCode;
  }

  public List<String> getWarningCodes() {
    return List.of(warningCodes);
  }

  public void setWarningCodes(List<String> warningCodes) {
    this.warningCodes = warningCodes.toArray(new String[0]);
  }
}
