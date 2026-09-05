package com.trackmywealth.backend.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.hibernate.annotations.Generated;
import org.hibernate.generator.EventType;

/**
 * Maps {@code household} (V2). The id is deliberately NOT {@code @GeneratedValue}: the bootstrap
 * sequence documented in {@code V19__seed_reference_data.sql} requires the application to know this
 * row's id before the INSERT is issued, so it can set {@code app.current_household_id} to that
 * value first (required for the row-level-security policies in V20 to allow the household's own
 * first rows to be written) - see {@code SetupService}.
 *
 * <p>{@code updated_at} and {@code version} are owned by the database ({@code
 * trg_set_updated_at}/{@code trg_bump_version} in V1): the trigger increments {@code version} and
 * rejects a write whose incoming value doesn't match the stored row, so Hibernate must never send
 * its own incremented value in the UPDATE statement - {@code @Generated} excludes both columns from
 * every INSERT/UPDATE and refreshes them from the database afterward instead.
 */
@Entity
@Table(name = "household")
public class Household {

  @Id
  @Column(columnDefinition = "uuid")
  private UUID id;

  @Column(nullable = false)
  private String name;

  @Column(nullable = false)
  private String status = "ACTIVE";

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

  public void setId(UUID id) {
    this.id = id;
  }

  public String getName() {
    return name;
  }

  public void setName(String name) {
    this.name = name;
  }

  public String getStatus() {
    return status;
  }

  public void setStatus(String status) {
    this.status = status;
  }

  public OffsetDateTime getCreatedAt() {
    return createdAt;
  }

  public OffsetDateTime getUpdatedAt() {
    return updatedAt;
  }

  public Integer getVersion() {
    return version;
  }
}
