package com.trackmywealth.backend.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.util.UUID;
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

  // FR-CC-007: the current account expected to pay this card's statement. Null until set;
  // settlement
  // matching only runs for a card that has one.
  @Column(name = "settlement_source_account_id", columnDefinition = "uuid")
  private UUID settlementSourceAccountId;

  public UUID getSettlementSourceAccountId() {
    return settlementSourceAccountId;
  }

  public void setSettlementSourceAccountId(UUID settlementSourceAccountId) {
    this.settlementSourceAccountId = settlementSourceAccountId;
  }

  public String getBillingCurrency() {
    return billingCurrency;
  }

  public void setBillingCurrency(String billingCurrency) {
    this.billingCurrency = billingCurrency;
  }
}
