package com.trackmywealth.backend.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.math.BigDecimal;

/** Maps {@code account_mortgage} (V5) - extension of {@link Account} for {@code MORTGAGE}. */
@Entity
@Table(name = "account_mortgage")
public class AccountMortgage extends AccountExtension {

  @Column(name = "original_principal", nullable = false)
  private BigDecimal originalPrincipal;

  @Column(name = "interest_rate_percent", nullable = false)
  private BigDecimal interestRatePercent;

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
}
