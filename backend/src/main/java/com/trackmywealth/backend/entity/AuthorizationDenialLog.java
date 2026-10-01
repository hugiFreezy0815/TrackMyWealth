package com.trackmywealth.backend.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.hibernate.annotations.Generated;
import org.hibernate.generator.EventType;

/**
 * Maps {@code authorization_denial_log} (V16) - FR-TEN-004/006 (US-28-02): every object-level
 * authorization denial on a single-resource endpoint, logged with {@code reason = NOT_FOUND}
 * whether the caller-supplied id genuinely doesn't exist or belongs to someone else - the two are
 * deliberately indistinguishable to the caller, so this audit trail deliberately doesn't record
 * which one actually happened either. {@code NOT_AUTHORIZED} (the column's other allowed value) is
 * reserved for a caller who knows exactly which resource they were denied - a role/permission
 * failure, not an identity-enumeration risk - which nothing writes to this table yet.
 * `RATE_LIMITED` is a #205 summary row emitted after one principal exhausts the exact-row budget
 * for a throttle window; it carries no requested id and preserves the probing signal without
 * unbounded growth.
 */
@Entity
@Table(name = "authorization_denial_log")
public class AuthorizationDenialLog {

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  @Column(columnDefinition = "uuid")
  private UUID id;

  @Column(name = "principal_user_id", columnDefinition = "uuid")
  private UUID principalUserId;

  @Column(name = "requested_entity_type", nullable = false)
  private String requestedEntityType;

  @Column(name = "requested_entity_id", columnDefinition = "uuid")
  private UUID requestedEntityId;

  @Column(nullable = false)
  private String reason;

  @Generated(event = EventType.INSERT)
  @Column(name = "occurred_at", insertable = false, updatable = false)
  private OffsetDateTime occurredAt;

  public UUID getId() {
    return id;
  }

  public UUID getPrincipalUserId() {
    return principalUserId;
  }

  public void setPrincipalUserId(UUID principalUserId) {
    this.principalUserId = principalUserId;
  }

  public String getRequestedEntityType() {
    return requestedEntityType;
  }

  public void setRequestedEntityType(String requestedEntityType) {
    this.requestedEntityType = requestedEntityType;
  }

  public UUID getRequestedEntityId() {
    return requestedEntityId;
  }

  public void setRequestedEntityId(UUID requestedEntityId) {
    this.requestedEntityId = requestedEntityId;
  }

  public String getReason() {
    return reason;
  }

  public void setReason(String reason) {
    this.reason = reason;
  }

  public OffsetDateTime getOccurredAt() {
    return occurredAt;
  }
}
