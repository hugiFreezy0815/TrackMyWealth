package com.trackmywealth.backend.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.hibernate.annotations.Generated;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.generator.EventType;
import org.hibernate.type.SqlTypes;

/**
 * Maps {@code loan_payment} (V83) - one payment from an ordinary account to a loan or mortgage and
 * how it was split into interest and principal (US-10-02, FR-CF-006). The ledger rows it names
 * carry the money; this row says where the split came from ({@code splitSource}) and whether it is
 * an estimate (PR-011). Written once, never updated by the application.
 */
@Entity
@Table(name = "loan_payment")
public class LoanPayment {

  private static final String UUID_COLUMN = "uuid";

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  @Column(columnDefinition = UUID_COLUMN)
  private UUID id;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "workspace_id", nullable = false)
  private Workspace workspace;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "loan_account_id", nullable = false)
  private Account loanAccount;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "paying_account_id", nullable = false)
  private Account payingAccount;

  @Column(name = "booking_date", nullable = false)
  private LocalDate bookingDate;

  @JdbcTypeCode(SqlTypes.CHAR)
  @Column(nullable = false, length = 3)
  private String currency;

  @Column(name = "total_amount", nullable = false, precision = 20, scale = 4)
  private BigDecimal totalAmount;

  @Column(name = "interest_amount", precision = 20, scale = 4)
  private BigDecimal interestAmount;

  @Column(name = "principal_amount", precision = 20, scale = 4)
  private BigDecimal principalAmount;

  @Column(name = "split_source", nullable = false)
  private String splitSource;

  @Column(name = "split_estimated", nullable = false)
  private boolean splitEstimated;

  @Column(name = "amortisation_schedule_entry_id", columnDefinition = UUID_COLUMN)
  private UUID amortisationScheduleEntryId;

  @Column(name = "principal_transaction_id", columnDefinition = UUID_COLUMN)
  private UUID principalTransactionId;

  @Column(name = "interest_transaction_id", columnDefinition = UUID_COLUMN)
  private UUID interestTransactionId;

  @Column(name = "external_id")
  private String externalId;

  @Column(name = "created_by", nullable = false, columnDefinition = UUID_COLUMN)
  private UUID createdBy;

  @Generated(event = EventType.INSERT)
  @Column(name = "created_at", insertable = false, updatable = false)
  private OffsetDateTime createdAt;

  public UUID getId() {
    return id;
  }

  public Workspace getWorkspace() {
    return workspace;
  }

  public void setWorkspace(Workspace workspace) {
    this.workspace = workspace;
  }

  public Account getLoanAccount() {
    return loanAccount;
  }

  public void setLoanAccount(Account loanAccount) {
    this.loanAccount = loanAccount;
  }

  public Account getPayingAccount() {
    return payingAccount;
  }

  public void setPayingAccount(Account payingAccount) {
    this.payingAccount = payingAccount;
  }

  public LocalDate getBookingDate() {
    return bookingDate;
  }

  public void setBookingDate(LocalDate bookingDate) {
    this.bookingDate = bookingDate;
  }

  public String getCurrency() {
    return currency;
  }

  public void setCurrency(String currency) {
    this.currency = currency;
  }

  public BigDecimal getTotalAmount() {
    return totalAmount;
  }

  public void setTotalAmount(BigDecimal totalAmount) {
    this.totalAmount = totalAmount;
  }

  public BigDecimal getInterestAmount() {
    return interestAmount;
  }

  public void setInterestAmount(BigDecimal interestAmount) {
    this.interestAmount = interestAmount;
  }

  public BigDecimal getPrincipalAmount() {
    return principalAmount;
  }

  public void setPrincipalAmount(BigDecimal principalAmount) {
    this.principalAmount = principalAmount;
  }

  public String getSplitSource() {
    return splitSource;
  }

  public void setSplitSource(String splitSource) {
    this.splitSource = splitSource;
  }

  public boolean isSplitEstimated() {
    return splitEstimated;
  }

  public void setSplitEstimated(boolean splitEstimated) {
    this.splitEstimated = splitEstimated;
  }

  public UUID getAmortisationScheduleEntryId() {
    return amortisationScheduleEntryId;
  }

  public void setAmortisationScheduleEntryId(UUID amortisationScheduleEntryId) {
    this.amortisationScheduleEntryId = amortisationScheduleEntryId;
  }

  public UUID getPrincipalTransactionId() {
    return principalTransactionId;
  }

  public void setPrincipalTransactionId(UUID principalTransactionId) {
    this.principalTransactionId = principalTransactionId;
  }

  public UUID getInterestTransactionId() {
    return interestTransactionId;
  }

  public void setInterestTransactionId(UUID interestTransactionId) {
    this.interestTransactionId = interestTransactionId;
  }

  public String getExternalId() {
    return externalId;
  }

  public void setExternalId(String externalId) {
    this.externalId = externalId;
  }

  public UUID getCreatedBy() {
    return createdBy;
  }

  public void setCreatedBy(UUID createdBy) {
    this.createdBy = createdBy;
  }

  public OffsetDateTime getCreatedAt() {
    return createdAt;
  }
}
