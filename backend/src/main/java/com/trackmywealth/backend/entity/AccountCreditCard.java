package com.trackmywealth.backend.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** Maps {@code account_credit_card} (V5) - extension of {@link Account} for {@code CREDIT_CARD}. */
@Entity
@Table(name = "account_credit_card")
public class AccountCreditCard extends AccountExtension {

  // V5 declares this CHAR(3) (fixed-width ISO 4217 code), not varchar.
  @JdbcTypeCode(SqlTypes.CHAR)
  @Column(name = "billing_currency", nullable = false, length = 3)
  private String billingCurrency;

  public String getBillingCurrency() {
    return billingCurrency;
  }

  public void setBillingCurrency(String billingCurrency) {
    this.billingCurrency = billingCurrency;
  }
}
