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
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.hibernate.annotations.Generated;
import org.hibernate.generator.EventType;

/**
 * Maps {@code amortisation_schedule_entry} (V5, versioned by V82) - one due date of a loan's or
 * mortgage's amortisation schedule as the lender states it (FR-NW-006): the interest and principal
 * parts of that instalment and the balance left after it. One row per {@code (account, dueDate)}.
 * US-10-02 splits a payment by the entry of its period ({@code LoanRepaymentService}).
 *
 * <p>A 1:many child of {@link Account}, protected transitively through it (V20): every access path
 * loads the account first.
 */
@Entity
@Table(name = "amortisation_schedule_entry")
public class AmortisationScheduleEntry {

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  @Column(columnDefinition = "uuid")
  private UUID id;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "account_id", nullable = false)
  private Account account;

  @Column(name = "due_date", nullable = false)
  private LocalDate dueDate;

  @Column(name = "interest_component", nullable = false, precision = 20, scale = 4)
  private BigDecimal interestComponent;

  @Column(name = "principal_component", nullable = false, precision = 20, scale = 4)
  private BigDecimal principalComponent;

  @Column(name = "remaining_balance", nullable = false, precision = 20, scale = 4)
  private BigDecimal remainingBalance;

  @Generated(event = EventType.INSERT)
  @Column(name = "created_at", insertable = false, updatable = false)
  private OffsetDateTime createdAt;

  @Version
  @Generated(event = {EventType.INSERT, EventType.UPDATE})
  @Column(name = "version", insertable = false, updatable = false)
  private Integer version;

  public UUID getId() {
    return id;
  }

  public Account getAccount() {
    return account;
  }

  public void setAccount(Account account) {
    this.account = account;
  }

  public LocalDate getDueDate() {
    return dueDate;
  }

  public void setDueDate(LocalDate dueDate) {
    this.dueDate = dueDate;
  }

  public BigDecimal getInterestComponent() {
    return interestComponent;
  }

  public void setInterestComponent(BigDecimal interestComponent) {
    this.interestComponent = interestComponent;
  }

  public BigDecimal getPrincipalComponent() {
    return principalComponent;
  }

  public void setPrincipalComponent(BigDecimal principalComponent) {
    this.principalComponent = principalComponent;
  }

  public BigDecimal getRemainingBalance() {
    return remainingBalance;
  }

  public void setRemainingBalance(BigDecimal remainingBalance) {
    this.remainingBalance = remainingBalance;
  }

  public OffsetDateTime getCreatedAt() {
    return createdAt;
  }

  public Integer getVersion() {
    return version;
  }

  /** The instalment this entry states: interest plus principal. */
  public BigDecimal getInstalment() {
    return interestComponent.add(principalComponent);
  }
}
