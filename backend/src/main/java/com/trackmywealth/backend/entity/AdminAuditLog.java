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
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.generator.EventType;
import org.hibernate.type.SqlTypes;

/**
 * Maps {@code admin_audit_log} (V17) - every administrative action on a user (US-02-01: create,
 * edit, disable, reactivate), kept deliberately separate from {@code financial_audit_log}
 * (FR-TEN-007: administration and financial-data access are different permission domains, and their
 * audit trails should not be joined casually either). {@code details} is raw JSON text, built by
 * the caller (e.g. via Jackson) before being stored - Postgres validates it is well-formed JSON on
 * insert since the column itself is {@code JSONB}.
 */
@Entity
@Table(name = "admin_audit_log")
public class AdminAuditLog {

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  @Column(columnDefinition = "uuid")
  private UUID id;

  @Column(name = "actor_user_id", columnDefinition = "uuid")
  private UUID actorUserId;

  @Column(nullable = false)
  private String action;

  @Column(name = "target_user_id", columnDefinition = "uuid")
  private UUID targetUserId;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(columnDefinition = "jsonb")
  private String details;

  @Generated(event = EventType.INSERT)
  @Column(name = "occurred_at", insertable = false, updatable = false)
  private OffsetDateTime occurredAt;

  public UUID getId() {
    return id;
  }

  public UUID getActorUserId() {
    return actorUserId;
  }

  public void setActorUserId(UUID actorUserId) {
    this.actorUserId = actorUserId;
  }

  public String getAction() {
    return action;
  }

  public void setAction(String action) {
    this.action = action;
  }

  public UUID getTargetUserId() {
    return targetUserId;
  }

  public void setTargetUserId(UUID targetUserId) {
    this.targetUserId = targetUserId;
  }

  public String getDetails() {
    return details;
  }

  public void setDetails(String details) {
    this.details = details;
  }

  public OffsetDateTime getOccurredAt() {
    return occurredAt;
  }
}
