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
 * Maps {@code account_pension} (V5) - extension of {@link Account} for {@code PENSION}. Whether it
 * holds positions is a capability flag on {@link Account} itself (a VIAC Pillar 3a does; a
 * PostFinance Pillar 3a may not, US-05-04) - {@code isOccupational} here is the
 * balance-and-entitlement-vs-position distinction for occupational (Pillar 2 / bAV) schemes.
 */
@Entity
@Table(name = "account_pension")
public class AccountPension {

  @Id
  @Column(name = "account_id")
  private UUID accountId;

  @OneToOne(fetch = FetchType.LAZY, optional = false)
  @MapsId
  @JoinColumn(name = "account_id")
  private Account account;

  @Column(name = "pension_scheme", nullable = false)
  private String pensionScheme;

  @Column(name = "is_occupational", nullable = false)
  private boolean occupational;

  public UUID getAccountId() {
    return accountId;
  }

  public Account getAccount() {
    return account;
  }

  public void setAccount(Account account) {
    this.account = account;
  }

  public String getPensionScheme() {
    return pensionScheme;
  }

  public void setPensionScheme(String pensionScheme) {
    this.pensionScheme = pensionScheme;
  }

  public boolean isOccupational() {
    return occupational;
  }

  public void setOccupational(boolean occupational) {
    this.occupational = occupational;
  }
}
