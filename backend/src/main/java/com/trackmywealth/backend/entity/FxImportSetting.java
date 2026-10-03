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
 * Maps {@code fx_import_setting} (V61, US-06-07): the one global row of FX import settings an
 * administrator changes at runtime. {@code importIntervalHours == null} means not set, so the
 * deployment's {@code app.fx.import.import-cron} applies. Never inserted by the application: V61
 * creates the row, so it has a version for If-Match from the start.
 *
 * <p>{@code updated_at} and {@code version} are owned by the database, as on {@link Workspace}.
 */
@Entity
@Table(name = "fx_import_setting")
public class FxImportSetting {

  @Id
  @Column(columnDefinition = "uuid")
  private UUID id;

  // V61 declares this SMALLINT, so the field is a Short (as AccountCreditCard.statementDay).
  @Column(name = "import_interval_hours")
  private Short importIntervalHours;

  @Column(name = "updated_by", columnDefinition = "uuid")
  private UUID updatedBy;

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

  public Integer getImportIntervalHours() {
    return importIntervalHours == null ? null : importIntervalHours.intValue();
  }

  // The application never clears it again: an administrator chooses another interval instead.
  public void setImportIntervalHours(int importIntervalHours) {
    this.importIntervalHours = (short) importIntervalHours;
  }

  public UUID getUpdatedBy() {
    return updatedBy;
  }

  public void setUpdatedBy(UUID updatedBy) {
    this.updatedBy = updatedBy;
  }

  public OffsetDateTime getUpdatedAt() {
    return updatedAt;
  }

  public Integer getVersion() {
    return version;
  }
}
