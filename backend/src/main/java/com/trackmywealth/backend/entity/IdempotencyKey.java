package com.trackmywealth.backend.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Maps {@code idempotency_key} (V124, US-29-14 #574): an {@code Idempotency-Key} a member sent on a
 * creating POST, scoped to the user, the workspace and the operation. {@code IN_PROGRESS} while the
 * request runs, {@code COMPLETED} with the stored response once the resource exists; handled as new
 * after {@code expiresAt}. Written only through {@code IdempotencyKeyRepository}'s statements.
 */
@Entity
@Table(name = "idempotency_key")
public class IdempotencyKey {

  public static final String IN_PROGRESS = "IN_PROGRESS";
  public static final String COMPLETED = "COMPLETED";

  @Id
  @Column(columnDefinition = "uuid")
  private UUID id;

  @Column(name = "workspace_id", nullable = false, updatable = false)
  private UUID workspaceId;

  @Column(name = "user_id", nullable = false, updatable = false)
  private UUID userId;

  @Column(name = "operation_id", nullable = false, updatable = false)
  private String operation;

  @Column(name = "idempotency_key", nullable = false, updatable = false)
  private String key;

  @JdbcTypeCode(SqlTypes.CHAR)
  @Column(name = "request_fingerprint", nullable = false, length = 64)
  private String requestFingerprint;

  @Column(nullable = false)
  private String status;

  @Column(name = "response_status")
  private Short responseStatus;

  @Column(name = "response_body")
  private String responseBody;

  @Column(name = "created_at", nullable = false)
  private OffsetDateTime createdAt;

  @Column(name = "expires_at", nullable = false)
  private OffsetDateTime expiresAt;

  public UUID getId() {
    return id;
  }

  public UUID getWorkspaceId() {
    return workspaceId;
  }

  public UUID getUserId() {
    return userId;
  }

  public String getOperation() {
    return operation;
  }

  public String getKey() {
    return key;
  }

  public String getRequestFingerprint() {
    return requestFingerprint;
  }

  public String getStatus() {
    return status;
  }

  public Short getResponseStatus() {
    return responseStatus;
  }

  public String getResponseBody() {
    return responseBody;
  }

  public OffsetDateTime getCreatedAt() {
    return createdAt;
  }

  public OffsetDateTime getExpiresAt() {
    return expiresAt;
  }
}
