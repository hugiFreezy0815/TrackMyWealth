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
import org.hibernate.annotations.DynamicUpdate;
import org.hibernate.annotations.Generated;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.generator.EventType;
import org.hibernate.type.SqlTypes;

/**
 * Maps {@code transaction} (V10, V31) - the append-only ledger (RULE-024/FR-TRX-007). Only the
 * columns the credit-card slice (US-09-01/02/04) writes or reads are mapped; every other column
 * (security, trade/settlement dates, void metadata, category, ...) is left to the story that first
 * needs it, and an unmapped column is neither read nor overwritten by Hibernate.
 *
 * <p>{@code amount} is <b>cash-direction signed</b> and stored exactly as the caller sent it: money
 * leaving the account is negative, money entering it is positive. For a {@code CREDIT_CARD} account
 * a purchase is therefore negative (it increases what is owed) and a settlement or refund is
 * positive. A financial field is never updated in place - {@code trg_transaction_append_only}
 * rejects it at the DB level.
 *
 * <p>US-09-04/DM-06/FR-CC-010: {@code currency} need not equal the account's own currency for a
 * foreign-currency card purchase - {@code amount} then stays in the <em>original</em> currency, and
 * {@code fxRateToAccountCurrency} (multiplied by {@code amount}) is what a summed balance must use
 * instead of the raw amount ({@code TransactionRepository}'s balance queries all do this). {@code
 * fxRateEstimated} distinguishes a disclosed-or-derived rate from a generic daily-rate fallback
 * (PR-011). {@code relatedTransactionId} links a distinct {@code FEE} row (a disclosed
 * foreign-transaction fee) back to the purchase it was charged on.
 */
// Only the columns that actually changed are written: the ledger's financial fields are frozen by
// trg_transaction_append_only, so an UPDATE that re-sent every mapped column would be one careless
// mapping change away from tripping it. Settlement matching (US-09-02) updates only the two link
// columns below.
@Entity
@DynamicUpdate
@Table(name = "transaction")
public class Transaction {

  // Named once and reused everywhere below - PMD's AvoidDuplicateLiterals flags the same string
  // literal appearing 4+ times in one file.
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

  // DM-05/FR-CF-001: an internal transfer (e.g. a card settlement) is excluded from income and
  // expense. Set together with counterpartyAccountId by settlement matching; both columns stay
  // mutable under the append-only trigger, which is what lets a wrong match be undone cleanly.
  @Column(name = "is_internal_transfer", nullable = false)
  private boolean internalTransfer;

  // FR-CF-005: the account holding the other leg of a matched transfer. Null until matched.
  @Column(name = "counterparty_account_id", columnDefinition = UUID_COLUMN)
  private UUID counterpartyAccountId;

  // DM-06: the rate that converts `amount` (in `currency`) into the account's own currency by
  // multiplication - null for an ordinary same-currency row. Frozen by trg_transaction_append_only
  // (V31), same as amount/currency themselves.
  @Column(name = "fx_rate_to_account_currency", precision = 20, scale = 10)
  private BigDecimal fxRateToAccountCurrency;

  // The date the rate above was resolved for - the transaction's own booking date, not "today"
  // (FR-CUR-011's valuation-date convention is for a *current* holding, not a historical rate).
  @Column(name = "fx_rate_date")
  private LocalDate fxRateDate;

  // V31/PR-011: true when fxRateToAccountCurrency is FxRateService's generic daily-rate fallback,
  // not an issuer-disclosed or source-derived one - see TransactionService for which applies.
  @Column(name = "fx_rate_estimated", nullable = false)
  private boolean fxRateEstimated;

  // V31: links a distinct FEE row (US-09-04, FR-CC-010: a disclosed foreign-transaction fee is
  // never folded into the purchase amount) back to the purchase it was charged on. Frozen, same as
  // the FX columns above - set once at insert, never reassigned.
  @Column(name = "related_transaction_id", columnDefinition = UUID_COLUMN)
  private UUID relatedTransactionId;

  // FR-LIF-002: set when the row is voided (US-07-02). Read-only here - no code path in this
  // codebase voids yet - so a matching query can leave voided rows out.
  @Column(name = "voided_at", insertable = false, updatable = false)
  private OffsetDateTime voidedAt;

  @Column(name = "created_by", columnDefinition = UUID_COLUMN)
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

  public boolean isInternalTransfer() {
    return internalTransfer;
  }

  public void setInternalTransfer(boolean internalTransfer) {
    this.internalTransfer = internalTransfer;
  }

  public UUID getCounterpartyAccountId() {
    return counterpartyAccountId;
  }

  public void setCounterpartyAccountId(UUID counterpartyAccountId) {
    this.counterpartyAccountId = counterpartyAccountId;
  }

  public BigDecimal getFxRateToAccountCurrency() {
    return fxRateToAccountCurrency;
  }

  public void setFxRateToAccountCurrency(BigDecimal fxRateToAccountCurrency) {
    this.fxRateToAccountCurrency = fxRateToAccountCurrency;
  }

  public LocalDate getFxRateDate() {
    return fxRateDate;
  }

  public void setFxRateDate(LocalDate fxRateDate) {
    this.fxRateDate = fxRateDate;
  }

  public boolean isFxRateEstimated() {
    return fxRateEstimated;
  }

  public void setFxRateEstimated(boolean fxRateEstimated) {
    this.fxRateEstimated = fxRateEstimated;
  }

  public UUID getRelatedTransactionId() {
    return relatedTransactionId;
  }

  public void setRelatedTransactionId(UUID relatedTransactionId) {
    this.relatedTransactionId = relatedTransactionId;
  }

  public OffsetDateTime getVoidedAt() {
    return voidedAt;
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
