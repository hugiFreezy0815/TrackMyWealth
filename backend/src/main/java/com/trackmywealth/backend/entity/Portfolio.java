package com.trackmywealth.backend.entity;

import com.trackmywealth.backend.dto.PortfolioType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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
 * Maps {@code portfolio} (V3, renamed from {@code financial_institution} by V111) - a user-named
 * container, not an account (RULE-001/002/003, FR-INS-001). The real bank or broker behind it is
 * optional: {@link #getCatalogueInstitution()} links a catalogue entry, and {@code country}, {@code
 * institutionType} and {@code logoUrl} describe it (FR-INS-003). The default "Personal Assets"
 * portfolio is created by V19's {@code workspace_create_personal_assets_container} trigger
 * immediately after a workspace is inserted; {@code SetupService} reads it back and corrects its
 * placeholder {@code CHF} currency to the workspace's actual chosen currency (see V19's trigger
 * comment).
 */
@Entity
@Table(name = "portfolio")
public class Portfolio {

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  @Column(columnDefinition = "uuid")
  private UUID id;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "workspace_id", nullable = false)
  private Workspace workspace;

  // US-04-01: null for a portfolio not backed by any catalogue entry (FR-INS-003).
  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "catalogue_institution_id")
  private InstitutionCatalogue catalogueInstitution;

  @Column(nullable = false)
  private String name;

  // ISO 3166-1 alpha-2, unlike institution_catalogue.country - no CHECK constraint restricts
  // this to CH/DE, since a custom (non-catalogue) institution may be anywhere. V3 declares this
  // CHAR(2) (fixed-width), not varchar, same as portfolioCurrency below.
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
  @Column(name = "portfolio_currency", nullable = false, length = 3)
  private String portfolioCurrency;

  @Column(name = "is_personal_assets_default", nullable = false)
  private boolean personalAssetsDefault;

  // V112, US-04-06: the type the member set (FR-INS-006), descriptive only (FR-INS-008). Null
  // while the type is suggested from the active accounts, which happens on every read and is
  // never stored (PortfolioService#typeOf).
  @Enumerated(EnumType.STRING)
  @Column(name = "portfolio_type_override")
  private PortfolioType portfolioTypeOverride;

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

  public String getPortfolioCurrency() {
    return portfolioCurrency;
  }

  public void setPortfolioCurrency(String portfolioCurrency) {
    this.portfolioCurrency = portfolioCurrency;
  }

  public boolean isPersonalAssetsDefault() {
    return personalAssetsDefault;
  }

  public void setPersonalAssetsDefault(boolean personalAssetsDefault) {
    this.personalAssetsDefault = personalAssetsDefault;
  }

  /** The type the member set; {@code null} while it is suggested from the accounts. */
  public PortfolioType getPortfolioTypeOverride() {
    return portfolioTypeOverride;
  }

  public void setPortfolioTypeOverride(PortfolioType portfolioTypeOverride) {
    this.portfolioTypeOverride = portfolioTypeOverride;
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
