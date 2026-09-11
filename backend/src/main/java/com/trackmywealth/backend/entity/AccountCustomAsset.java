package com.trackmywealth.backend.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

/**
 * Maps {@code account_custom_asset} (V5) - extension of {@link Account} for {@code CUSTOM_ASSET}
 * (real estate, vehicles, precious metals, collectibles).
 */
@Entity
@Table(name = "account_custom_asset")
public class AccountCustomAsset extends AccountExtension {

  @Column(name = "custom_asset_type", nullable = false)
  private String customAssetType;

  @Column(name = "valuation_frequency")
  private String valuationFrequency = "MANUAL";

  public String getCustomAssetType() {
    return customAssetType;
  }

  public void setCustomAssetType(String customAssetType) {
    this.customAssetType = customAssetType;
  }

  public String getValuationFrequency() {
    return valuationFrequency;
  }

  public void setValuationFrequency(String valuationFrequency) {
    this.valuationFrequency = valuationFrequency;
  }
}
