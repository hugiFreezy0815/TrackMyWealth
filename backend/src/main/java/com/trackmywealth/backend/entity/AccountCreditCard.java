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

  // FR-CC-008/US-09-03: the day of the month a statement closes (1-31; a month shorter than that
  // closes on its last day - see CardStatementService). Null until configured; the statement view
  // is unavailable until it is. V5 declares this SMALLINT, so the Java type is Short, not Integer
  // (hbm2ddl validation is strict about the two) - CardStatementService converts at the DTO
  // boundary so the REST API itself still deals in plain integers.
  @Column(name = "statement_day")
  private Short statementDay;

  // FR-CC-008: days from a statement's closing date to its payment due date. Null until
  // configured, independently of statementDay. Also SMALLINT (V5) - see statementDay above.
  @Column(name = "due_date_offset_days")
  private Short dueDateOffsetDays;

  public UUID getSettlementSourceAccountId() {
    return settlementSourceAccountId;
  }

  public void setSettlementSourceAccountId(UUID settlementSourceAccountId) {
    this.settlementSourceAccountId = settlementSourceAccountId;
  }

  public Short getStatementDay() {
    return statementDay;
  }

  public void setStatementDay(Short statementDay) {
    this.statementDay = statementDay;
  }

  public Short getDueDateOffsetDays() {
    return dueDateOffsetDays;
  }

  public void setDueDateOffsetDays(Short dueDateOffsetDays) {
    this.dueDateOffsetDays = dueDateOffsetDays;
  }

  public String getBillingCurrency() {
    return billingCurrency;
  }

  public void setBillingCurrency(String billingCurrency) {
    this.billingCurrency = billingCurrency;
  }
}
