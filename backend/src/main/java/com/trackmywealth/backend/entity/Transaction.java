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
 * Maps {@code transaction} (V10) - the append-only ledger (RULE-024/FR-TRX-007). Only the columns
 * the credit-card slice (US-09-01) writes or reads are mapped; every other column (security,
 * trade/settlement dates, FX, void metadata, category, ...) is left to the story that first needs
 * it, and an unmapped column is neither read nor overwritten by Hibernate.
 *
 * <p>{@code amount} is <b>cash-direction signed</b> and stored exactly as the caller sent it: money
 * leaving the account is negative, money entering it is positive. For a {@code CREDIT_CARD} account
 * a purchase is therefore negative (it increases what is owed) and a settlement or refund is
 * positive. A financial field is never updated in place - {@code trg_transaction_append_only}
 * rejects it at the DB level.
 */
@Entity
@Table(name = "transaction")
public class Transaction {

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  @Column(columnDefinition = "uuid")
  private UUID id;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "workspace_id", nullable = false)
  private Workspace workspace;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "account_id", nullable = false)
  private Account account;

  @Column(name = "transaction_type", nullable = false)
  private String transactionType;

  @Column(name = "booking_date", nullable = false)
  private LocalDate bookingDate;

  @Column(nullable = false, precision = 20, scale = 4)
  private BigDecimal amount;

  // V10 declares this CHAR(3) (fixed-width ISO 4217 code), not varchar.
  @JdbcTypeCode(SqlTypes.CHAR)
  @Column(nullable = false, length = 3)
  private String currency;

  @Column(name = "merchant_description")
  private String merchantDescription;

  private String notes;

  @Column(nullable = false)
  private String source = "MANUAL";

  // DB-04/FR-TRX-009: unique per (account_id, source, external_id) - the idempotency key.
  @Column(name = "external_id")
  private String externalId;

  // FR-CC-002/RULE-011: the source-provided MCC lives here, never in category_id - so a later
  // (re)categorization can only ever touch category_id, not the original source data (FR-TRX-003).
  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "raw_source_data", columnDefinition = "jsonb")
  private String rawSourceData;

  @Column(name = "created_by", columnDefinition = "uuid")
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

  public Account getAccount() {
    return account;
  }

  public void setAccount(Account account) {
    this.account = account;
  }

  public String getTransactionType() {
    return transactionType;
  }

  public void setTransactionType(String transactionType) {
    this.transactionType = transactionType;
  }

  public LocalDate getBookingDate() {
    return bookingDate;
  }

  public void setBookingDate(LocalDate bookingDate) {
    this.bookingDate = bookingDate;
  }

  public BigDecimal getAmount() {
    return amount;
  }

  public void setAmount(BigDecimal amount) {
    this.amount = amount;
  }

  public String getCurrency() {
    return currency;
  }

  public void setCurrency(String currency) {
    this.currency = currency;
  }

  public String getMerchantDescription() {
    return merchantDescription;
  }

  public void setMerchantDescription(String merchantDescription) {
    this.merchantDescription = merchantDescription;
  }

  public String getNotes() {
    return notes;
  }

  public void setNotes(String notes) {
    this.notes = notes;
  }

  public String getSource() {
    return source;
  }

  public void setSource(String source) {
    this.source = source;
  }

  public String getExternalId() {
    return externalId;
  }

  public void setExternalId(String externalId) {
    this.externalId = externalId;
  }

  public String getRawSourceData() {
    return rawSourceData;
  }

  public void setRawSourceData(String rawSourceData) {
    this.rawSourceData = rawSourceData;
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
