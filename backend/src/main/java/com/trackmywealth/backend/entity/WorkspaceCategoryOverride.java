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
 * Maps {@code workspace_category_override} (V34): one workspace's relabelling and/or deactivation
 * of a shared default {@link Category} (US-08-04). A NULL field inherits the shipped value, so a
 * later reference package relabelling a default still reaches workspaces that never changed it.
 * V44 allows an all-NULL row to remain as an optimistic-concurrency revision tombstone after a
 * workspace reverts to the shipped values; {@link #isEmpty()} distinguishes that state from an
 * effective customization.
 */
@Entity
@Table(name = "workspace_category_override")
public class WorkspaceCategoryOverride {

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  @Column(columnDefinition = "uuid")
  private UUID id;

  @Column(name = "workspace_id", nullable = false, updatable = false)
  private UUID workspaceId;

  @Column(name = "category_id", nullable = false, updatable = false)
  private UUID categoryId;

  @Column(name = "name_en")
  private String nameEn;

  @Column(name = "name_de")
  private String nameDe;

  @Column(name = "is_active")
  private Boolean active;

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

  protected WorkspaceCategoryOverride() {}

  public WorkspaceCategoryOverride(UUID workspaceId, UUID categoryId) {
    this.workspaceId = workspaceId;
    this.categoryId = categoryId;
  }

  public UUID getId() {
    return id;
  }

  public UUID getWorkspaceId() {
    return workspaceId;
  }

  public UUID getCategoryId() {
    return categoryId;
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

  public Boolean getActive() {
    return active;
  }

  public void setActive(Boolean active) {
    this.active = active;
  }

  public Integer getVersion() {
    return version;
  }

  /** True when every field inherits the shipped value; the row may still persist as a revision. */
  public boolean isEmpty() {
    return nameEn == null && nameDe == null && active == null;
  }
}
