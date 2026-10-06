package com.trackmywealth.backend.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.UUID;
import org.hibernate.annotations.Generated;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.generator.EventType;
import org.hibernate.type.SqlTypes;

/**
 * Maps {@code import_file} (V67): the original bytes of one import batch's file (US-07-04,
 * FR-IMP-013), kept so the batch can be re-parsed with a corrected template. Financial personal
 * data: workspace RLS, never logged, deleted when the batch is discarded.
 */
@Entity
@Table(name = "import_file")
public class ImportFile {

  @Id
  @Column(name = "import_batch_id", columnDefinition = "uuid")
  private UUID importBatchId;

  @Column(name = "workspace_id", nullable = false, updatable = false)
  private UUID workspaceId;

  @Column(nullable = false, updatable = false)
  private byte[] content;

  @JdbcTypeCode(SqlTypes.CHAR)
  @Column(nullable = false, length = 64, updatable = false)
  private String sha256;

  @Column(name = "size_bytes", nullable = false, updatable = false)
  private int sizeBytes;

  @Column(name = "media_type", updatable = false)
  private String mediaType;

  @Generated(event = EventType.INSERT)
  @Column(name = "created_at", insertable = false, updatable = false)
  private OffsetDateTime createdAt;

  public UUID getImportBatchId() {
    return importBatchId;
  }

  public void setImportBatchId(UUID importBatchId) {
    this.importBatchId = importBatchId;
  }

  public UUID getWorkspaceId() {
    return workspaceId;
  }

  public void setWorkspaceId(UUID workspaceId) {
    this.workspaceId = workspaceId;
  }

  public byte[] getContent() {
    return Arrays.copyOf(content, content.length);
  }

  public void setContent(byte[] content) {
    this.content = Arrays.copyOf(content, content.length);
    this.sizeBytes = content.length;
  }

  public String getSha256() {
    return sha256;
  }

  public void setSha256(String sha256) {
    this.sha256 = sha256;
  }

  public int getSizeBytes() {
    return sizeBytes;
  }

  public String getMediaType() {
    return mediaType;
  }

  public void setMediaType(String mediaType) {
    this.mediaType = mediaType;
  }

  public OffsetDateTime getCreatedAt() {
    return createdAt;
  }
}
