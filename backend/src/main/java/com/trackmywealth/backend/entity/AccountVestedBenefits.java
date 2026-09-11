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
import java.time.LocalDate;
import java.util.UUID;

/**
 * Maps {@code account_vested_benefits} (V5) - extension of {@link Account} for {@code
 * VESTED_BENEFITS}. Every column is nullable at the DB level - nothing here is required at creation
 * time.
 */
@Entity
@Table(name = "account_vested_benefits")
public class AccountVestedBenefits {

  @Id
  @Column(name = "account_id")
  private UUID accountId;

  @OneToOne(fetch = FetchType.LAZY, optional = false)
  @MapsId
  @JoinColumn(name = "account_id")
  private Account account;

  @Column(name = "vested_benefit_amount")
  private BigDecimal vestedBenefitAmount;

  @Column(name = "interest_credit_rate_percent")
  private BigDecimal interestCreditRatePercent;

  @Column(name = "last_certificate_date")
  private LocalDate lastCertificateDate;

  public UUID getAccountId() {
    return accountId;
  }

  public Account getAccount() {
    return account;
  }

  public void setAccount(Account account) {
    this.account = account;
  }

  public BigDecimal getVestedBenefitAmount() {
    return vestedBenefitAmount;
  }

  public void setVestedBenefitAmount(BigDecimal vestedBenefitAmount) {
    this.vestedBenefitAmount = vestedBenefitAmount;
  }

  public BigDecimal getInterestCreditRatePercent() {
    return interestCreditRatePercent;
  }

  public void setInterestCreditRatePercent(BigDecimal interestCreditRatePercent) {
    this.interestCreditRatePercent = interestCreditRatePercent;
  }

  public LocalDate getLastCertificateDate() {
    return lastCertificateDate;
  }

  public void setLastCertificateDate(LocalDate lastCertificateDate) {
    this.lastCertificateDate = lastCertificateDate;
  }
}
