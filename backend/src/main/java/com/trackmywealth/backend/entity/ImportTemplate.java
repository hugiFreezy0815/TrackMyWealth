package com.trackmywealth.backend.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.hibernate.annotations.Generated;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.generator.EventType;
import org.hibernate.type.SqlTypes;

/**
 * Maps {@code import_template} (V15, V64): one version of a CSV import template (US-07-03). A NULL
 * {@code workspaceId} is a shipped template, read-only to every workspace (V20's RLS). The versions
 * of one template share {@code templateFamilyId}; exactly one is current. A version's
 * parse-relevant fields never change after it was written - a change writes a new version - so a
 * batch that used it stays reproducible (FR-IMP-023).
 *
 * <p>{@code columnMapping}, {@code typeMapping} and {@code headerColumns} are JSONB documents kept
 * as their JSON text; {@code ImportTemplateService} reads and writes them as typed values.
 */
@Entity
@Table(name = "import_template")
public class ImportTemplate {

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  @Column(columnDefinition = "uuid")
  private UUID id;

  @Column(name = "template_family_id", nullable = false, updatable = false)
  private UUID templateFamilyId;

  @Column(name = "institution_catalogue_id")
  private UUID institutionCatalogueId;

  @Column(name = "workspace_id", updatable = false)
  private UUID workspaceId;

  @Column(nullable = false)
  private String name;

  @Column(name = "template_class", nullable = false, updatable = false)
  private String templateClass;

  @Column(name = "template_version", nullable = false, updatable = false)
  private String templateVersion;

  @Column(name = "effective_from", nullable = false, updatable = false)
  private LocalDate effectiveFrom;

  @Column(nullable = false, updatable = false)
  private String delimiter;

  @Column(nullable = false, updatable = false)
  private String encoding;

  @Column(name = "decimal_separator", nullable = false, updatable = false)
  private String decimalSeparator;

  @Column(name = "thousands_separator", updatable = false)
  private String thousandsSeparator;

  @Column(name = "date_format", nullable = false, updatable = false)
  private String dateFormat;

  @Column(name = "header_row_index", nullable = false, updatable = false)
  private Short headerRowIndex;

  @Column(name = "preamble_row_count", nullable = false, updatable = false)
  private Short preambleRowCount;

  @Column(name = "trailing_summary_row_count", nullable = false, updatable = false)
  private Short trailingSummaryRowCount;

  @Column(name = "amount_representation", nullable = false, updatable = false)
  private String amountRepresentation;

  @Column(name = "currency_mode", nullable = false, updatable = false)
  private String currencyMode;

  @JdbcTypeCode(SqlTypes.CHAR)
  @Column(name = "fixed_currency", length = 3, updatable = false)
  private String fixedCurrency;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "column_mapping", columnDefinition = "jsonb", nullable = false, updatable = false)
  private String columnMapping;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "type_mapping", columnDefinition = "jsonb", nullable = false, updatable = false)
  private String typeMapping;

  @Column(name = "account_identification_strategy", nullable = false, updatable = false)
  private String accountIdentificationStrategy;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "header_columns", columnDefinition = "jsonb", updatable = false)
  private String headerColumns;

  @Column(name = "header_fingerprint", updatable = false)
  private String headerFingerprint;

  @Column(name = "is_system_provided", nullable = false, updatable = false)
  private boolean systemProvided;

  @Column(name = "is_current", nullable = false)
  private boolean current = true;

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

  public UUID getTemplateFamilyId() {
    return templateFamilyId;
  }

  public void setTemplateFamilyId(UUID templateFamilyId) {
    this.templateFamilyId = templateFamilyId;
  }

  public UUID getInstitutionCatalogueId() {
    return institutionCatalogueId;
  }

  public void setInstitutionCatalogueId(UUID institutionCatalogueId) {
    this.institutionCatalogueId = institutionCatalogueId;
  }

  public UUID getWorkspaceId() {
    return workspaceId;
  }

  public void setWorkspaceId(UUID workspaceId) {
    this.workspaceId = workspaceId;
  }

  public String getName() {
    return name;
  }

  public void setName(String name) {
    this.name = name;
  }

  public String getTemplateClass() {
    return templateClass;
  }

  public void setTemplateClass(String templateClass) {
    this.templateClass = templateClass;
  }

  public String getTemplateVersion() {
    return templateVersion;
  }

  public void setTemplateVersion(String templateVersion) {
    this.templateVersion = templateVersion;
  }

  public LocalDate getEffectiveFrom() {
    return effectiveFrom;
  }

  public void setEffectiveFrom(LocalDate effectiveFrom) {
    this.effectiveFrom = effectiveFrom;
  }

  public String getDelimiter() {
    return delimiter;
  }

  public void setDelimiter(String delimiter) {
    this.delimiter = delimiter;
  }

  public String getEncoding() {
    return encoding;
  }

  public void setEncoding(String encoding) {
    this.encoding = encoding;
  }

  public String getDecimalSeparator() {
    return decimalSeparator;
  }

  public void setDecimalSeparator(String decimalSeparator) {
    this.decimalSeparator = decimalSeparator;
  }

  public String getThousandsSeparator() {
    return thousandsSeparator;
  }

  public void setThousandsSeparator(String thousandsSeparator) {
    this.thousandsSeparator = thousandsSeparator;
  }

  public String getDateFormat() {
    return dateFormat;
  }

  public void setDateFormat(String dateFormat) {
    this.dateFormat = dateFormat;
  }

  public Short getHeaderRowIndex() {
    return headerRowIndex;
  }

  public void setHeaderRowIndex(Short headerRowIndex) {
    this.headerRowIndex = headerRowIndex;
  }

  public Short getPreambleRowCount() {
    return preambleRowCount;
  }

  public void setPreambleRowCount(Short preambleRowCount) {
    this.preambleRowCount = preambleRowCount;
  }

  public Short getTrailingSummaryRowCount() {
    return trailingSummaryRowCount;
  }

  public void setTrailingSummaryRowCount(Short trailingSummaryRowCount) {
    this.trailingSummaryRowCount = trailingSummaryRowCount;
  }

  public String getAmountRepresentation() {
    return amountRepresentation;
  }

  public void setAmountRepresentation(String amountRepresentation) {
    this.amountRepresentation = amountRepresentation;
  }

  public String getCurrencyMode() {
    return currencyMode;
  }

  public void setCurrencyMode(String currencyMode) {
    this.currencyMode = currencyMode;
  }

  public String getFixedCurrency() {
    return fixedCurrency;
  }

  public void setFixedCurrency(String fixedCurrency) {
    this.fixedCurrency = fixedCurrency;
  }

  public String getColumnMapping() {
    return columnMapping;
  }

  public void setColumnMapping(String columnMapping) {
    this.columnMapping = columnMapping;
  }

  public String getTypeMapping() {
    return typeMapping;
  }

  public void setTypeMapping(String typeMapping) {
    this.typeMapping = typeMapping;
  }

  public String getAccountIdentificationStrategy() {
    return accountIdentificationStrategy;
  }

  public void setAccountIdentificationStrategy(String accountIdentificationStrategy) {
    this.accountIdentificationStrategy = accountIdentificationStrategy;
  }

  public String getHeaderColumns() {
    return headerColumns;
  }

  public void setHeaderColumns(String headerColumns) {
    this.headerColumns = headerColumns;
  }

  public String getHeaderFingerprint() {
    return headerFingerprint;
  }

  public void setHeaderFingerprint(String headerFingerprint) {
    this.headerFingerprint = headerFingerprint;
  }

  public boolean isSystemProvided() {
    return systemProvided;
  }

  /** A shipped template, shared by every workspace and never edited by one. */
  public boolean isShared() {
    return workspaceId == null;
  }

  public boolean isCurrent() {
    return current;
  }

  public void setCurrent(boolean current) {
    this.current = current;
  }

  public boolean isActive() {
    return active;
  }

  public void setActive(boolean active) {
    this.active = active;
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
