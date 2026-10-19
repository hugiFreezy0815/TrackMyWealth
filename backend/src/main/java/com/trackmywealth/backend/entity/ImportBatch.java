package com.trackmywealth.backend.entity;

import com.trackmywealth.backend.dto.ImportBatchValues;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.hibernate.annotations.Generated;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.generator.EventType;
import org.hibernate.type.SqlTypes;

/**
 * Maps {@code import_batch} (V15, V67, V68): one uploaded file for one account and what became of
 * it (US-07-04). Its status moves {@code UPLOADED -> PARSED -> COMMITTED}, or to {@code DISCARDED}
 * before the commit, and a rollback ends a committed batch {@code ROLLED_BACK} or {@code VOIDED}
 * (US-07-05); see {@link ImportBatchValues}. The file itself is an {@link ImportFile}, its rows
 * {@link ImportRowRaw}s, so reading a batch never loads either.
 */
@Entity
@Table(name = "import_batch")
public class ImportBatch {

  private static final String UUID_COLUMN = "uuid";

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  @Column(columnDefinition = UUID_COLUMN)
  private UUID id;

  @Column(name = "workspace_id", nullable = false, updatable = false)
  private UUID workspaceId;

  @Column(name = "account_id", nullable = false, updatable = false)
  private UUID accountId;

  @Column(name = "template_id")
  private UUID templateId;

  // FR-IMP-023: the exact version the rows were parsed with, even if the template changes later.
  @Column(name = "template_version_used")
  private String templateVersionUsed;

  @Column(name = "account_identification")
  private String accountIdentification;

  @Column(name = "proposed_account_id")
  private UUID proposedAccountId;

  @Column(name = "account_confirmation_required", nullable = false)
  private boolean accountConfirmationRequired;

  @Column(name = "account_confirmed", nullable = false)
  private boolean accountConfirmed;

  @Column(name = "source_file_name", updatable = false)
  private String sourceFileName;

  // "db": the file is in import_file. Kept as a reference so another store stays possible.
  @Column(name = "source_file_storage_ref")
  private String sourceFileStorageRef;

  @JdbcTypeCode(SqlTypes.CHAR)
  @Column(name = "file_sha256", length = 64, updatable = false)
  private String fileSha256;

  @Column(name = "source_kind", nullable = false)
  private String sourceKind = ImportBatchValues.SOURCE_CSV;

  @Column(nullable = false)
  private String status = ImportBatchValues.UPLOADED;

  @Column(name = "row_count")
  private Integer rowCount;

  @Column(name = "imported_row_count")
  private Integer importedRowCount;

  @Column(name = "duplicate_row_count")
  private Integer duplicateRowCount;

  @Column(name = "error_row_count")
  private Integer errorRowCount;

  @Column(name = "contains_modified_records", nullable = false)
  private boolean containsModifiedRecords;

  @Generated(event = EventType.INSERT)
  @Column(name = "uploaded_at", insertable = false, updatable = false)
  private OffsetDateTime uploadedAt;

  @Column(name = "parsed_at")
  private OffsetDateTime parsedAt;

  @Column(name = "committed_at")
  private OffsetDateTime committedAt;

  @Column(name = "uploaded_by", columnDefinition = UUID_COLUMN, updatable = false)
  private UUID uploadedBy;

  @Column(name = "rolled_back_at")
  private OffsetDateTime rolledBackAt;

  @Column(name = "rolled_back_by", columnDefinition = UUID_COLUMN)
  private UUID rolledBackBy;

  @Column(name = "rollback_reason")
  private String rollbackReason;

  // US-07-12: the balance the file states, as its parse read it, and what became of it.
  @Column(name = "statement_balance_outcome")
  private String statementBalanceOutcome;

  @Column(name = "statement_balance_date")
  private LocalDate statementBalanceDate;

  @Column(name = "statement_balance")
  private BigDecimal statementBalance;

  @JdbcTypeCode(SqlTypes.CHAR)
  @Column(name = "statement_balance_currency", length = 3)
  private String statementBalanceCurrency;

  @Column(name = "opening_balance_date")
  private LocalDate openingBalanceDate;

  @Column(name = "opening_balance")
  private BigDecimal openingBalance;

  @Column(name = "statement_balance_snapshot")
  private String statementBalanceSnapshot;

  // US-07-13 (#273): a holdings statement's snapshot date, where it came from, and what the commit
  // (and later a rollback) did with the snapshot. Null for a cash statement's batch.
  @Column(name = "holdings_snapshot_date")
  private LocalDate holdingsSnapshotDate;

  @Column(name = "holdings_date_source")
  private String holdingsDateSource;

  @Column(name = "holdings_snapshot")
  private String holdingsSnapshot;

  @Version
  @Generated(event = {EventType.INSERT, EventType.UPDATE})
  @Column(name = "version", insertable = false, updatable = false)
  private Integer version;

  public UUID getId() {
    return id;
  }

  public UUID getWorkspaceId() {
    return workspaceId;
  }

  public void setWorkspaceId(UUID workspaceId) {
    this.workspaceId = workspaceId;
  }

  public UUID getAccountId() {
    return accountId;
  }

  public void setAccountId(UUID accountId) {
    this.accountId = accountId;
  }

  public UUID getTemplateId() {
    return templateId;
  }

  public void setTemplateId(UUID templateId) {
    this.templateId = templateId;
  }

  public String getTemplateVersionUsed() {
    return templateVersionUsed;
  }

  public void setTemplateVersionUsed(String templateVersionUsed) {
    this.templateVersionUsed = templateVersionUsed;
  }

  public String getAccountIdentification() {
    return accountIdentification;
  }

  public void setAccountIdentification(String accountIdentification) {
    this.accountIdentification = accountIdentification;
  }

  public UUID getProposedAccountId() {
    return proposedAccountId;
  }

  public void setProposedAccountId(UUID proposedAccountId) {
    this.proposedAccountId = proposedAccountId;
  }

  public boolean isAccountConfirmationRequired() {
    return accountConfirmationRequired;
  }

  public void setAccountConfirmationRequired(boolean accountConfirmationRequired) {
    this.accountConfirmationRequired = accountConfirmationRequired;
  }

  public boolean isAccountConfirmed() {
    return accountConfirmed;
  }

  public void setAccountConfirmed(boolean accountConfirmed) {
    this.accountConfirmed = accountConfirmed;
  }

  public String getSourceFileName() {
    return sourceFileName;
  }

  public void setSourceFileName(String sourceFileName) {
    this.sourceFileName = sourceFileName;
  }

  public String getSourceFileStorageRef() {
    return sourceFileStorageRef;
  }

  public void setSourceFileStorageRef(String sourceFileStorageRef) {
    this.sourceFileStorageRef = sourceFileStorageRef;
  }

  public String getFileSha256() {
    return fileSha256;
  }

  public void setFileSha256(String fileSha256) {
    this.fileSha256 = fileSha256;
  }

  public String getSourceKind() {
    return sourceKind;
  }

  public void setSourceKind(String sourceKind) {
    this.sourceKind = sourceKind;
  }

  public String getStatus() {
    return status;
  }

  public void setStatus(String status) {
    this.status = status;
  }

  public Integer getRowCount() {
    return rowCount;
  }

  public void setRowCount(Integer rowCount) {
    this.rowCount = rowCount;
  }

  public Integer getImportedRowCount() {
    return importedRowCount;
  }

  public void setImportedRowCount(Integer importedRowCount) {
    this.importedRowCount = importedRowCount;
  }

  public Integer getDuplicateRowCount() {
    return duplicateRowCount;
  }

  public void setDuplicateRowCount(Integer duplicateRowCount) {
    this.duplicateRowCount = duplicateRowCount;
  }

  public Integer getErrorRowCount() {
    return errorRowCount;
  }

  public void setErrorRowCount(Integer errorRowCount) {
    this.errorRowCount = errorRowCount;
  }

  public boolean isContainsModifiedRecords() {
    return containsModifiedRecords;
  }

  public void setContainsModifiedRecords(boolean containsModifiedRecords) {
    this.containsModifiedRecords = containsModifiedRecords;
  }

  public OffsetDateTime getUploadedAt() {
    return uploadedAt;
  }

  public OffsetDateTime getParsedAt() {
    return parsedAt;
  }

  public void setParsedAt(OffsetDateTime parsedAt) {
    this.parsedAt = parsedAt;
  }

  public OffsetDateTime getCommittedAt() {
    return committedAt;
  }

  public void setCommittedAt(OffsetDateTime committedAt) {
    this.committedAt = committedAt;
  }

  public UUID getUploadedBy() {
    return uploadedBy;
  }

  public void setUploadedBy(UUID uploadedBy) {
    this.uploadedBy = uploadedBy;
  }

  public OffsetDateTime getRolledBackAt() {
    return rolledBackAt;
  }

  public UUID getRolledBackBy() {
    return rolledBackBy;
  }

  public String getRollbackReason() {
    return rollbackReason;
  }

  /** US-07-05: who rolled the batch back, when and why (V68 requires all three together). */
  public void recordRollback(OffsetDateTime at, UUID by, String reason) {
    this.rolledBackAt = at;
    this.rolledBackBy = by;
    this.rollbackReason = reason;
  }

  /**
   * US-07-12: what a parse read as the file's balance; every value but {@code outcome} is {@code
   * null} unless it is {@code FOUND} or {@code OUT_OF_RANGE}, the opening balance also when the
   * file states only one. Only a batch before its commit is parsed, so no snapshot outcome is
   * recorded yet.
   */
  public void recordStatementBalance(
      String outcome,
      LocalDate date,
      BigDecimal balance,
      String currency,
      LocalDate openingDate,
      BigDecimal opening) {
    this.statementBalanceOutcome = outcome;
    this.statementBalanceDate = date;
    this.statementBalance = balance;
    this.statementBalanceCurrency = currency;
    this.openingBalanceDate = openingDate;
    this.openingBalance = opening;
  }

  /**
   * US-07-12: the balance's date is no longer one the account admits (its dates changed since the
   * parse); the commit records no snapshot. The balance read stays, for the batch's summary.
   */
  public void markStatementBalanceOutOfRange() {
    this.statementBalanceOutcome = ImportBatchValues.BALANCE_OUT_OF_RANGE;
  }

  public String getStatementBalanceOutcome() {
    return statementBalanceOutcome;
  }

  public LocalDate getStatementBalanceDate() {
    return statementBalanceDate;
  }

  public BigDecimal getStatementBalance() {
    return statementBalance;
  }

  public String getStatementBalanceCurrency() {
    return statementBalanceCurrency;
  }

  public LocalDate getOpeningBalanceDate() {
    return openingBalanceDate;
  }

  public BigDecimal getOpeningBalance() {
    return openingBalance;
  }

  public String getStatementBalanceSnapshot() {
    return statementBalanceSnapshot;
  }

  public void setStatementBalanceSnapshot(String statementBalanceSnapshot) {
    this.statementBalanceSnapshot = statementBalanceSnapshot;
  }

  /**
   * US-07-13: a parse read a holdings statement of {@code date} from {@code dateSource}; both
   * {@code null} clear it, when the batch is parsed again as a cash statement. Only a batch before
   * its commit is parsed, so it has no snapshot outcome yet.
   */
  public void recordHoldingsStatement(LocalDate date, String dateSource) {
    this.holdingsSnapshotDate = date;
    this.holdingsDateSource = dateSource;
  }

  /** Whether the batch holds a holdings statement (US-07-13) rather than cash rows. */
  public boolean isHoldingsStatement() {
    return holdingsSnapshotDate != null;
  }

  public LocalDate getHoldingsSnapshotDate() {
    return holdingsSnapshotDate;
  }

  public String getHoldingsDateSource() {
    return holdingsDateSource;
  }

  public String getHoldingsSnapshot() {
    return holdingsSnapshot;
  }

  public void setHoldingsSnapshot(String holdingsSnapshot) {
    this.holdingsSnapshot = holdingsSnapshot;
  }

  public Integer getVersion() {
    return version;
  }
}
