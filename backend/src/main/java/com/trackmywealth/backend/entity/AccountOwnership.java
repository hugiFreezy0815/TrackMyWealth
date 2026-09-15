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
import org.hibernate.generator.EventType;

/**
 * Maps {@code account_ownership} (V6) - a dated relationship between a {@link WorkspaceMember} and
 * an {@link Account} (US-03-02, FR-HOU-002/003/006). Never a column on {@code Account} itself: this
 * is what lets a person-scoped figure apply {@code ownershipShare} while a workspace-scoped figure
 * counts the account's value exactly once regardless of how many owners it has (FR-HOU-005) - the
 * latter falls out of the data model for free, since {@code account} itself has exactly one row per
 * account no matter how many {@code AccountOwnership} rows fan out from it.
 *
 * <p>{@code effectiveTo} is null while this row is the account's currently-effective ownership for
 * this member; V6's {@code uq_account_ownership_current} partial unique index enforces at most one
 * such row per {@code (account, member)} pair. An ownership change closes the old row (sets {@code
 * effectiveTo}) and opens a new one (FR-HOU-006) - never an in-place edit, the same "a correction
 * is a new dated row" pattern US-05-05's {@code CustomAssetValuation} already uses.
 */
@Entity
@Table(name = "account_ownership")
public class AccountOwnership {

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  @Column(columnDefinition = "uuid")
  private UUID id;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "account_id", nullable = false)
  private Account account;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "workspace_member_id", nullable = false)
  private WorkspaceMember workspaceMember;

  @Column(name = "ownership_share", nullable = false)
  private BigDecimal ownershipShare;

  @Column(name = "effective_from", nullable = false)
  private LocalDate effectiveFrom;

  @Column(name = "effective_to")
  private LocalDate effectiveTo;

  @Generated(event = EventType.INSERT)
  @Column(name = "created_at", insertable = false, updatable = false)
  private OffsetDateTime createdAt;

  public UUID getId() {
    return id;
  }

  public Account getAccount() {
    return account;
  }

  public void setAccount(Account account) {
    this.account = account;
  }

  public WorkspaceMember getWorkspaceMember() {
    return workspaceMember;
  }

  public void setWorkspaceMember(WorkspaceMember workspaceMember) {
    this.workspaceMember = workspaceMember;
  }

  public BigDecimal getOwnershipShare() {
    return ownershipShare;
  }

  public void setOwnershipShare(BigDecimal ownershipShare) {
    this.ownershipShare = ownershipShare;
  }

  public LocalDate getEffectiveFrom() {
    return effectiveFrom;
  }

  public void setEffectiveFrom(LocalDate effectiveFrom) {
    this.effectiveFrom = effectiveFrom;
  }

  public LocalDate getEffectiveTo() {
    return effectiveTo;
  }

  public void setEffectiveTo(LocalDate effectiveTo) {
    this.effectiveTo = effectiveTo;
  }

  public OffsetDateTime getCreatedAt() {
    return createdAt;
  }
}
