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
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.generator.EventType;
import org.hibernate.type.SqlTypes;

/**
 * Maps {@code account_snapshot} (V11, V33): an institution-reported <em>observation</em> of an
 * account's state on a date, never derived from the ledger (RULE-025, FR-REC-001). One row per
 * {@code (account, snapshotDate, source)}.
 *
 * <p>{@code balance} follows the same convention as the account's ledger-derived balance ({@code
 * GET /accounts/{id}/balance}): a liability's balance is the positive amount owed. It is {@code
 * null} for a depot snapshot that reports positions only. {@code currency} is always the account's
 * own currency - {@code native_currency}, or a credit card's {@code billing_currency} (V58's guard
 * trigger). {@code openingBalance} marks the account's one opening balance (US-25-04, V58).
 *
 * <p>V48 adds an optimistic-lock revision for FR-CNC-001/002. The existing row lock still keeps
 * replacement of the holdings set atomic; the client-facing revision additionally protects the
 * longer read-edit-write interval.
 */
@Entity
@Table(name = "account_snapshot")
public class AccountSnapshot {

  private static final String UUID_COLUMN = "uuid";

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  @Column(columnDefinition = UUID_COLUMN)
  private UUID id;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "workspace_id", nullable = false)
  private Workspace workspace;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "account_id", nullable = false)
  private Account account;

  @Column(name = "snapshot_date", nullable = false)
  private LocalDate snapshotDate;

  private BigDecimal balance;

  @JdbcTypeCode(SqlTypes.CHAR)
  @Column(nullable = false, length = 3)
  private String currency;

  @Column(nullable = false)
  private String source;

  @Column(name = "is_opening_balance", nullable = false)
  private boolean openingBalance;

  @Generated(event = EventType.INSERT)
  @Column(name = "created_at", insertable = false, updatable = false)
  private OffsetDateTime createdAt;

  @Column(name = "created_by", columnDefinition = UUID_COLUMN, updatable = false)
  private UUID createdBy;

  @Column(name = "updated_at")
  private OffsetDateTime updatedAt;

  @Column(name = "updated_by", columnDefinition = UUID_COLUMN)
  private UUID updatedBy;

  @Version
  @Generated(event = {EventType.INSERT, EventType.UPDATE})
  @Column(name = "version", insertable = false, updatable = false)
  private Integer version;

  public UUID getId() {
    return id;
  }

  public Workspace getWorkspace() {
    return workspace;
  }

  public void setWorkspace(Workspace workspace) {
    this.workspace = workspace;
  }

  public Account getAccount() {
    return account;
  }

  public void setAccount(Account account) {
    this.account = account;
  }

  public LocalDate getSnapshotDate() {
    return snapshotDate;
  }

  public void setSnapshotDate(LocalDate snapshotDate) {
    this.snapshotDate = snapshotDate;
  }

  public BigDecimal getBalance() {
    return balance;
  }

  public void setBalance(BigDecimal balance) {
    this.balance = balance;
  }

  public String getCurrency() {
    return currency;
  }

  public void setCurrency(String currency) {
    this.currency = currency;
  }

  public String getSource() {
    return source;
  }

  public void setSource(String source) {
    this.source = source;
  }

  public boolean isOpeningBalance() {
    return openingBalance;
  }

  public void setOpeningBalance(boolean openingBalance) {
    this.openingBalance = openingBalance;
  }

  public OffsetDateTime getCreatedAt() {
    return createdAt;
  }

  public UUID getCreatedBy() {
    return createdBy;
  }

  public void setCreatedBy(UUID createdBy) {
    this.createdBy = createdBy;
  }

  public OffsetDateTime getUpdatedAt() {
    return updatedAt;
  }

  public void setUpdatedAt(OffsetDateTime updatedAt) {
    this.updatedAt = updatedAt;
  }

  /**
   * When the figure was last stated: its replacement, else its creation. An opening balance stated
   * after a reconciliation adjustment on its own date already contains it (US-25-03). For an
   * opening balance both come from the database's clock, as the adjustment's {@code created_at}
   * does, so the two compare without skew.
   */
  public OffsetDateTime getStatedAt() {
    return updatedAt != null ? updatedAt : createdAt;
  }

  public Integer getVersion() {
    return version;
  }

  public UUID getUpdatedBy() {
    return updatedBy;
  }

  public void setUpdatedBy(UUID updatedBy) {
    this.updatedBy = updatedBy;
  }
}
