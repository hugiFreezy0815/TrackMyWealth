package com.trackmywealth.backend.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.hibernate.annotations.Generated;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.generator.EventType;
import org.hibernate.type.SqlTypes;

/**
 * Maps {@code financial_institution} (V3) - a container, not an account (RULE-001/002/003). The
 * default "Personal Assets" container is created by V19's {@code
 * workspace_create_personal_assets_container} trigger immediately after a workspace is inserted;
 * {@code SetupService} reads it back and corrects its placeholder {@code CHF} currency to the
 * workspace's actual chosen currency (see V19's trigger comment).
 */
@Entity
@Table(name = "financial_institution")
public class FinancialInstitution {

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  @Column(columnDefinition = "uuid")
  private UUID id;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "workspace_id", nullable = false)
  private Workspace workspace;

  // US-04-01: null for a custom institution not backed by any catalogue entry (FR-INS-003).
  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "catalogue_institution_id")
  private InstitutionCatalogue catalogueInstitution;

  @Column(nullable = false)
  private String name;

  // ISO 3166-1 alpha-2, unlike institution_catalogue.country - no CHECK constraint restricts
  // this to CH/DE, since a custom (non-catalogue) institution may be anywhere. V3 declares this
  // CHAR(2) (fixed-width), not varchar, same as containerCurrency below.
  @JdbcTypeCode(SqlTypes.CHAR)
  @Column(length = 2)
  private String country;

  @Column(name = "institution_type", nullable = false)
  private String institutionType;

  @Column private String identifier;

  @Column(name = "logo_url")
  private String logoUrl;

  // V3 declares this CHAR(3) (fixed-width ISO 4217 code), not varchar.
  @JdbcTypeCode(SqlTypes.CHAR)
  @Column(name = "container_currency", nullable = false, length = 3)
  private String containerCurrency;

  @Column(name = "is_personal_assets_default", nullable = false)
  private boolean personalAssetsDefault;

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

  public Workspace getWorkspace() {
    return workspace;
  }

  public void setWorkspace(Workspace workspace) {
    this.workspace = workspace;
  }

  public InstitutionCatalogue getCatalogueInstitution() {
    return catalogueInstitution;
  }

  public void setCatalogueInstitution(InstitutionCatalogue catalogueInstitution) {
    this.catalogueInstitution = catalogueInstitution;
  }

  public String getName() {
    return name;
  }

  public void setName(String name) {
    this.name = name;
  }

  public String getCountry() {
    return country;
  }

  public void setCountry(String country) {
    this.country = country;
  }

  public String getInstitutionType() {
    return institutionType;
  }

  public void setInstitutionType(String institutionType) {
    this.institutionType = institutionType;
  }

  public String getIdentifier() {
    return identifier;
  }

  public void setIdentifier(String identifier) {
    this.identifier = identifier;
  }

  public String getLogoUrl() {
    return logoUrl;
  }

  public void setLogoUrl(String logoUrl) {
    this.logoUrl = logoUrl;
  }

  public String getContainerCurrency() {
    return containerCurrency;
  }

  public void setContainerCurrency(String containerCurrency) {
    this.containerCurrency = containerCurrency;
  }

  public boolean isPersonalAssetsDefault() {
    return personalAssetsDefault;
  }

  public void setPersonalAssetsDefault(boolean personalAssetsDefault) {
    this.personalAssetsDefault = personalAssetsDefault;
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
