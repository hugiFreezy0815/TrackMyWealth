package com.trackmywealth.backend.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.util.UUID;

/** Maps {@code account_mortgage} (V5) - extension of {@link Account} for {@code MORTGAGE}. */
@Entity
@Table(name = "account_mortgage")
public class AccountMortgage extends AccountExtension {

  @Column(name = "original_principal", nullable = false)
  private BigDecimal originalPrincipal;

  @Column(name = "interest_rate_percent", nullable = false)
  private BigDecimal interestRatePercent;

  // V5: the real estate this mortgage finances (FR-POR-04/FR-NW-007), shown with it in the
  // Portfolio View (US-04-06). Read-only here: nothing sets the link yet.
  @Column(
      name = "linked_asset_account_id",
      columnDefinition = "uuid",
      insertable = false,
      updatable = false)
  private UUID linkedAssetAccountId;

  public BigDecimal getOriginalPrincipal() {
    return originalPrincipal;
  }

  public void setOriginalPrincipal(BigDecimal originalPrincipal) {
    this.originalPrincipal = originalPrincipal;
  }

  public BigDecimal getInterestRatePercent() {
    return interestRatePercent;
  }

  public void setInterestRatePercent(BigDecimal interestRatePercent) {
    this.interestRatePercent = interestRatePercent;
  }

  public UUID getLinkedAssetAccountId() {
    return linkedAssetAccountId;
  }
}
