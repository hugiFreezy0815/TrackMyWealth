package com.trackmywealth.backend.entity;

import com.trackmywealth.backend.dto.ReconciliationResultValues;
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
import java.time.OffsetDateTime;
import java.util.UUID;
import org.hibernate.annotations.Generated;
import org.hibernate.generator.EventType;

/**
 * One reconciliation comparison between an account's derived state and an observed snapshot
 * (US-25-02). The first implementation is cash-balance scope only, so {@code affectedSecurityId}
 * stays {@code null}; the existing column is kept mapped for the later holdings reconciliation.
 */
@Entity
@Table(name = "reconciliation_result")
public class ReconciliationResult {

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

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "snapshot_id", nullable = false)
  private AccountSnapshot snapshot;

  @Column(name = "affected_security_id", columnDefinition = UUID_COLUMN)
  private UUID affectedSecurityId;

  @Column(name = "difference_amount", precision = 20, scale = 4)
  private BigDecimal differenceAmount;

  @Column(name = "difference_quantity", precision = 28, scale = 10)
  private BigDecimal differenceQuantity;

  @Column(name = "probable_cause")
  private String probableCause;

  @Column(nullable = false)
  private String status = ReconciliationResultValues.OPEN;

  @Column(name = "resolution_note")
  private String resolutionNote;

  @Column(name = "resolution_transaction_id", columnDefinition = UUID_COLUMN)
  private UUID resolutionTransactionId;

  @Column(name = "resolved_at")
  private OffsetDateTime resolvedAt;

  @Column(name = "resolved_by", columnDefinition = UUID_COLUMN)
  private UUID resolvedBy;

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

  public Account getAccount() {
    return account;
  }

  public void setAccount(Account account) {
    this.account = account;
  }

  public AccountSnapshot getSnapshot() {
    return snapshot;
  }

  public void setSnapshot(AccountSnapshot snapshot) {
    this.snapshot = snapshot;
  }

  public UUID getAffectedSecurityId() {
    return affectedSecurityId;
  }

  public void setAffectedSecurityId(UUID affectedSecurityId) {
    this.affectedSecurityId = affectedSecurityId;
  }

  public BigDecimal getDifferenceAmount() {
    return differenceAmount;
  }

  public void setDifferenceAmount(BigDecimal differenceAmount) {
    this.differenceAmount = differenceAmount;
  }

  public BigDecimal getDifferenceQuantity() {
    return differenceQuantity;
  }

  public void setDifferenceQuantity(BigDecimal differenceQuantity) {
    this.differenceQuantity = differenceQuantity;
  }

  public String getProbableCause() {
    return probableCause;
  }

  public void setProbableCause(String probableCause) {
    this.probableCause = probableCause;
  }

  public String getStatus() {
    return status;
  }

  public void setStatus(String status) {
    this.status = status;
  }

  public String getResolutionNote() {
    return resolutionNote;
  }

  public void setResolutionNote(String resolutionNote) {
    this.resolutionNote = resolutionNote;
  }

  public UUID getResolutionTransactionId() {
    return resolutionTransactionId;
  }

  public void setResolutionTransactionId(UUID resolutionTransactionId) {
    this.resolutionTransactionId = resolutionTransactionId;
  }

  public OffsetDateTime getResolvedAt() {
    return resolvedAt;
  }

  public void setResolvedAt(OffsetDateTime resolvedAt) {
    this.resolvedAt = resolvedAt;
  }

  public UUID getResolvedBy() {
    return resolvedBy;
  }

  public void setResolvedBy(UUID resolvedBy) {
    this.resolvedBy = resolvedBy;
  }

  public OffsetDateTime getCreatedAt() {
    return createdAt;
  }
}
