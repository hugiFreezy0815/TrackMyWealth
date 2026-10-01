package com.trackmywealth.backend.entity;

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
import org.hibernate.generator.EventType;

/**
 * Maps {@code categorization_rule} (V13): a workspace's own rule assigning a category to matching
 * transactions (FR-CAT-007, US-08-01). {@code matchType} and {@code matchValue} are fixed at
 * creation; a rule is retired by deactivating it, so the log rows that name it keep their meaning.
 * RLS-protected by workspace (V20).
 */
@Entity
@Table(name = "categorization_rule")
public class CategorizationRule {

  private static final String UUID_COLUMN = "uuid";

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  @Column(columnDefinition = UUID_COLUMN)
  private UUID id;

  @Column(
      name = "workspace_id",
      nullable = false,
      updatable = false,
      columnDefinition = UUID_COLUMN)
  private UUID workspaceId;

  @Column(name = "match_type", nullable = false, updatable = false)
  private String matchType;

  @Column(name = "match_value", nullable = false, updatable = false)
  private String matchValue;

  @Column(name = "category_id", nullable = false, updatable = false, columnDefinition = UUID_COLUMN)
  private UUID categoryId;

  @Column(nullable = false, updatable = false)
  private int priority;

  @Column(name = "is_active", nullable = false)
  private boolean active = true;

  @Generated(event = EventType.INSERT)
  @Column(name = "created_at", insertable = false, updatable = false)
  private OffsetDateTime createdAt;

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

  public String getMatchType() {
    return matchType;
  }

  public void setMatchType(String matchType) {
    this.matchType = matchType;
  }

  public String getMatchValue() {
    return matchValue;
  }

  public void setMatchValue(String matchValue) {
    this.matchValue = matchValue;
  }

  public UUID getCategoryId() {
    return categoryId;
  }

  public void setCategoryId(UUID categoryId) {
    this.categoryId = categoryId;
  }

  public int getPriority() {
    return priority;
  }

  public void setPriority(int priority) {
    this.priority = priority;
  }

  public boolean isActive() {
    return active;
  }

  public void setActive(boolean active) {
    this.active = active;
  }

  public Integer getVersion() {
    return version;
  }

  public OffsetDateTime getCreatedAt() {
    return createdAt;
  }
}
