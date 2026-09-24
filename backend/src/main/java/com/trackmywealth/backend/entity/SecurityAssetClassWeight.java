package com.trackmywealth.backend.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Maps {@code security_asset_class_weight} (V7, FR-CLS-004/DM-24): a security is a weighted set of
 * asset classes. A manually created security gets a single 100% row flagged {@code estimated}
 * (FR-CLS-005: a declared allocation, not look-through data).
 */
@Entity
@Table(name = "security_asset_class_weight")
public class SecurityAssetClassWeight {

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  @Column(columnDefinition = "uuid")
  private UUID id;

  @Column(name = "security_id", nullable = false, columnDefinition = "uuid")
  private UUID securityId;

  @Column(name = "asset_class", nullable = false)
  private String assetClass;

  @Column(nullable = false, precision = 6, scale = 5)
  private BigDecimal weight;

  @Column(name = "is_estimated", nullable = false)
  private boolean estimated;

  // FR-SMD-009: reference data is effective-dated. Read-only here - the column's CURRENT_DATE
  // default assigns it, and a classification is never back-dated by an edit; a reclassification
  // adds a row for a later date (V7's UNIQUE (security_id, asset_class, effective_date)).
  @Column(name = "effective_date", insertable = false, updatable = false)
  private LocalDate effectiveDate;

  private String source;

  public UUID getId() {
    return id;
  }

  public UUID getSecurityId() {
    return securityId;
  }

  public void setSecurityId(UUID securityId) {
    this.securityId = securityId;
  }

  public String getAssetClass() {
    return assetClass;
  }

  public void setAssetClass(String assetClass) {
    this.assetClass = assetClass;
  }

  public BigDecimal getWeight() {
    return weight;
  }

  public void setWeight(BigDecimal weight) {
    this.weight = weight;
  }

  public boolean isEstimated() {
    return estimated;
  }

  public void setEstimated(boolean estimated) {
    this.estimated = estimated;
  }

  public LocalDate getEffectiveDate() {
    return effectiveDate;
  }

  public String getSource() {
    return source;
  }

  public void setSource(String source) {
    this.source = source;
  }
}
