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
 * Maps {@code category} (V13, V34): the canonical reporting taxonomy (DM-22, FR-CAT-001..003). A
 * NULL {@code workspaceId} is a shipped default shared by every workspace; RLS (V20) makes such a
 * row read-only to workspaces, so a workspace's relabelling or deactivation of it lives in {@link
 * WorkspaceCategoryOverride} instead. {@code code} is the stable identity reports key on
 * (FR-CAT-008) and never changes after creation, hence {@code updatable = false}.
 *
 * <p>Ids are kept as plain UUIDs rather than associations: the service loads a workspace's whole
 * (small) taxonomy at once and walks the tree in memory.
 */
@Entity
@Table(name = "category")
public class Category {

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  @Column(columnDefinition = "uuid")
  private UUID id;

  @Column(name = "workspace_id", updatable = false)
  private UUID workspaceId;

  @Column(name = "parent_category_id")
  private UUID parentCategoryId;

  @Column(nullable = false, updatable = false)
  private String code;

  @Column(name = "name_en", nullable = false)
  private String nameEn;

  @Column(name = "name_de", nullable = false)
  private String nameDe;

  @Column(name = "is_system_default", nullable = false, updatable = false)
  private boolean systemDefault;

  @Column(name = "is_active", nullable = false)
  private boolean active = true;

  @Generated(event = EventType.INSERT)
  @Column(name = "created_at", insertable = false, updatable = false)
  private OffsetDateTime createdAt;

  @Generated(event = {EventType.INSERT, EventType.UPDATE})
  @Column(name = "updated_at", insertable = false, updatable = false)
  private OffsetDateTime updatedAt;

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

  public UUID getParentCategoryId() {
    return parentCategoryId;
  }

  public void setParentCategoryId(UUID parentCategoryId) {
    this.parentCategoryId = parentCategoryId;
  }

  public String getCode() {
    return code;
  }

  public void setCode(String code) {
    this.code = code;
  }

  public String getNameEn() {
    return nameEn;
  }

  public void setNameEn(String nameEn) {
    this.nameEn = nameEn;
  }

  public String getNameDe() {
    return nameDe;
  }

  public void setNameDe(String nameDe) {
    this.nameDe = nameDe;
  }

  public boolean isSystemDefault() {
    return systemDefault;
  }

  public boolean isActive() {
    return active;
  }

  public void setActive(boolean active) {
    this.active = active;
  }

  /** A shipped default, shared by every workspace and never edited by one. */
  public boolean isShared() {
    return workspaceId == null;
  }

  public Integer getVersion() {
    return version;
  }
}
