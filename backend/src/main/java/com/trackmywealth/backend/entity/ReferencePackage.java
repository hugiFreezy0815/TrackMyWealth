package com.trackmywealth.backend.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.hibernate.annotations.Immutable;

/**
 * Maps {@code reference_package} (V18): one version of the shipped or imported reference data -
 * institution catalogue, default categories, source-code mappings, fallback sector taxonomy
 * (FR-REF-001/010). Exactly one is current. Read-only here: packages arrive by migration (the
 * baseline, V19/V43) or by the package import of EPIC 32.
 */
@Entity
@Immutable
@Table(name = "reference_package")
public class ReferencePackage {

  @Id
  @Column(columnDefinition = "uuid")
  private UUID id;

  @Column(name = "package_version", nullable = false)
  private String packageVersion;

  @Column(name = "publication_date", nullable = false)
  private LocalDate publicationDate;

  @Column(name = "imported_at")
  private OffsetDateTime importedAt;

  // NULL for a baseline shipped with the application: no member imported it.
  @Column(name = "imported_by", columnDefinition = "uuid")
  private UUID importedBy;

  @Column(name = "is_current", nullable = false)
  private boolean current;

  public UUID getId() {
    return id;
  }

  public String getPackageVersion() {
    return packageVersion;
  }

  public LocalDate getPublicationDate() {
    return publicationDate;
  }

  public OffsetDateTime getImportedAt() {
    return importedAt;
  }

  public UUID getImportedBy() {
    return importedBy;
  }

  public boolean isCurrent() {
    return current;
  }
}
