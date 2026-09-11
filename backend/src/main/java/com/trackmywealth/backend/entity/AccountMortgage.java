package com.trackmywealth.backend.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.MapsId;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.util.UUID;

/** Maps {@code account_mortgage} (V5) - extension of {@link Account} for {@code MORTGAGE}. */
@Entity
@Table(name = "account_mortgage")
public class AccountMortgage {

  @Id
  @Column(name = "account_id")
  private UUID accountId;

  @OneToOne(fetch = FetchType.LAZY, optional = false)
  @MapsId
  @JoinColumn(name = "account_id")
  private Account account;

  @Column(name = "original_principal", nullable = false)
  private BigDecimal originalPrincipal;

  @Column(name = "interest_rate_percent", nullable = false)
  private BigDecimal interestRatePercent;

  public UUID getAccountId() {
    return accountId;
  }

  public Account getAccount() {
    return account;
  }

  public void setAccount(Account account) {
    this.account = account;
  }

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
