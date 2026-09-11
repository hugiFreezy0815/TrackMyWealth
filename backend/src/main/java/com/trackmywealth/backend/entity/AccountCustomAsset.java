package com.trackmywealth.backend.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.MapsId;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import java.util.UUID;

/**
 * Maps {@code account_custom_asset} (V5) - extension of {@link Account} for {@code CUSTOM_ASSET}
 * (real estate, vehicles, precious metals, collectibles).
 */
@Entity
@Table(name = "account_custom_asset")
public class AccountCustomAsset {

  @Id
  @Column(name = "account_id")
  private UUID accountId;

  @OneToOne(fetch = FetchType.LAZY, optional = false)
  @MapsId
  @JoinColumn(name = "account_id")
  private Account account;

  @Column(name = "custom_asset_type", nullable = false)
  private String customAssetType;

  @Column(name = "valuation_frequency")
  private String valuationFrequency = "MANUAL";

  public UUID getAccountId() {
    return accountId;
  }

  public Account getAccount() {
    return account;
  }

  public void setAccount(Account account) {
    this.account = account;
  }

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
