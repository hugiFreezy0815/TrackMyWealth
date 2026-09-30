package com.trackmywealth.backend.entity;

import com.trackmywealth.backend.dto.SettlementMatchValues;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.hibernate.annotations.Generated;
import org.hibernate.generator.EventType;

/**
 * Maps {@code settlement_match} (V30): the decision, and its state, that a payment out of a card's
 * settlement-source account settles that card (US-09-02, FR-CC-004/005/007). The ledger rows
 * themselves are only ever touched through their two mutable link columns ({@link
 * Transaction#isInternalTransfer}, {@link Transaction#getCounterpartyAccountId}).
 */
@Entity
@Table(name = "settlement_match")
public class SettlementMatch {

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  @Column(columnDefinition = "uuid")
  private UUID id;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "workspace_id", nullable = false)
  private Workspace workspace;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "card_account_id", nullable = false)
  private Account cardAccount;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "payment_transaction_id", nullable = false)
  private Transaction paymentTransaction;

  // Null for a one-sided candidate until the card-side leg is recorded.
  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "card_transaction_id")
  private Transaction cardTransaction;

  // The same column as a plain id, read-only. A soft-deleted leg (US-07-02) is hidden by
  // Transaction's @SQLRestriction, so a fetch join yields no row for it: queries compare this id
  // with the joined leg to tell "one-sided" apart from "card leg currently deleted".
  @Column(
      name = "card_transaction_id",
      columnDefinition = "uuid",
      insertable = false,
      updatable = false)
  private UUID cardTransactionId;

  @Column(nullable = false)
  private String status;

  @Column(name = "match_basis", nullable = false)
  private String matchBasis;

  // US-10-01 (V41): CARD_SETTLEMENT or TRANSFER. For a transfer, paymentTransaction is the debit
  // leg, cardTransaction the credit leg and cardAccount the credit leg's account.
  @Column(name = "match_kind", nullable = false)
  private String matchKind = SettlementMatchValues.CARD_SETTLEMENT;

  // Null when the system decided (an unambiguous exact pair, or a competing proposal that lost).
  @Column(name = "decided_by", columnDefinition = "uuid")
  private UUID decidedBy;

  @Column(name = "decided_at")
  private OffsetDateTime decidedAt;

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

  public Account getCardAccount() {
    return cardAccount;
  }

  public void setCardAccount(Account cardAccount) {
    this.cardAccount = cardAccount;
  }

  public Transaction getPaymentTransaction() {
    return paymentTransaction;
  }

  public void setPaymentTransaction(Transaction paymentTransaction) {
    this.paymentTransaction = paymentTransaction;
  }

  public Transaction getCardTransaction() {
    return cardTransaction;
  }

  public void setCardTransaction(Transaction cardTransaction) {
    this.cardTransaction = cardTransaction;
  }

  public String getStatus() {
    return status;
  }

  public void setStatus(String status) {
    this.status = status;
  }

  public String getMatchBasis() {
    return matchBasis;
  }

  public void setMatchBasis(String matchBasis) {
    this.matchBasis = matchBasis;
  }

  public String getMatchKind() {
    return matchKind;
  }

  public void setMatchKind(String matchKind) {
    this.matchKind = matchKind;
  }

  /** US-10-01: an own-account transfer pair rather than a card settlement. */
  public boolean isTransfer() {
    return SettlementMatchValues.TRANSFER.equals(matchKind);
  }

  public UUID getDecidedBy() {
    return decidedBy;
  }

  public void setDecidedBy(UUID decidedBy) {
    this.decidedBy = decidedBy;
  }

  public OffsetDateTime getDecidedAt() {
    return decidedAt;
  }

  public void setDecidedAt(OffsetDateTime decidedAt) {
    this.decidedAt = decidedAt;
  }

  public OffsetDateTime getCreatedAt() {
    return createdAt;
  }
}
