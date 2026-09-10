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
 * Maps {@code institution_catalogue} (V3) - a shared, non-tenant-scoped seed-data catalogue of
 * CH/DE institutions (FR-INS-007), searchable when a workspace member creates a {@link
 * FinancialInstitution}. Maintained as configuration (see V18's reference-data-distribution
 * framework), not written by any application code path yet - only read.
 */
@Entity
@Table(name = "institution_catalogue")
public class InstitutionCatalogue {

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  @Column(columnDefinition = "uuid")
  private UUID id;

  @Column(nullable = false)
  private String name;

  // V3 declares this CHAR(2) (fixed-width), not varchar.
  @JdbcTypeCode(SqlTypes.CHAR)
  @Column(nullable = false, length = 2)
  private String country;

  @Column(name = "institution_type", nullable = false)
  private String institutionType;

  @Column private String identifier;

  @Column(name = "logo_url")
  private String logoUrl;

  @Column(name = "is_active", nullable = false)
  private boolean active;

  @Generated(event = EventType.INSERT)
  @Column(name = "created_at", insertable = false, updatable = false)
  private OffsetDateTime createdAt;

  @Generated(event = {EventType.INSERT, EventType.UPDATE})
  @Column(name = "updated_at", insertable = false, updatable = false)
  private OffsetDateTime updatedAt;

  public UUID getId() {
    return id;
  }

  public String getName() {
    return name;
  }

  public String getCountry() {
    return country;
  }

  public String getInstitutionType() {
    return institutionType;
  }

  public String getIdentifier() {
    return identifier;
  }

  public String getLogoUrl() {
    return logoUrl;
  }

  public boolean isActive() {
    return active;
  }

  public OffsetDateTime getCreatedAt() {
    return createdAt;
  }

  public OffsetDateTime getUpdatedAt() {
    return updatedAt;
  }
}
