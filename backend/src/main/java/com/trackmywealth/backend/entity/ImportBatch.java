package com.trackmywealth.backend.entity;

import com.trackmywealth.backend.dto.ImportBatchValues;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.hibernate.annotations.Generated;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.generator.EventType;
import org.hibernate.type.SqlTypes;

/**
 * Maps {@code import_batch} (V15, V67): one uploaded file for one account and what became of it
 * (US-07-04). Its status moves {@code UPLOADED -> PARSED -> COMMITTED}, or to {@code DISCARDED}
 * before the commit; see {@link ImportBatchValues}. The file itself is an {@link ImportFile}, its
 * rows {@link ImportRowRaw}s, so reading a batch never loads either.
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

  public Integer getVersion() {
    return version;
  }
}
