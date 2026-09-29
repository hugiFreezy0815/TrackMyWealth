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
import org.hibernate.annotations.SQLRestriction;
import org.hibernate.generator.EventType;
import org.hibernate.type.SqlTypes;

/**
 * Maps {@code transaction} (V10, V31) - the append-only ledger (RULE-024/FR-TRX-007). Only the
 * columns the stories so far (US-09-01/02/04, US-07-01, US-08-01) write or read are mapped; every
 * other column (void metadata, import batch, ...) is left to the story that first needs it, and an
 * unmapped column is neither read nor overwritten by Hibernate.
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
 *
 * <p>US-07-01 investment types ({@code BUY}, {@code SELL}, {@code DIVIDEND}): {@code securityId}
 * references the shared security master. {@code quantity} is <b>position-direction signed</b> (a
 * buy adds, a sale removes), so a position is the sum of {@code quantity} over the position-moving
 * types; a dividend's quantity (shares entitled) is informational and never moves a position.
 * {@code unitPrice} and {@code feeAmount} are positive magnitudes in {@code currency}; the fee of a
 * trade is part of its {@code amount}, not a separate row. {@code tradeDate} and {@code
 * settlementDate} are kept distinct (FR-TRX-008). A dividend's {@code grossAmount} minus {@code
 * taxWithheldAmount} is its {@code netAmount}, which equals {@code amount} (FR-TAXR-001).
 */
// Only the columns that actually changed are written: the ledger's financial fields are frozen by
// trg_transaction_append_only, so an UPDATE that re-sent every mapped column would be one careless
// mapping change away from tripping it. Settlement matching (US-09-02) updates only the two link
// columns below.
@Entity
@DynamicUpdate
// US-07-02: a soft-deleted row (T1) is gone from every JPA query - lists, balances, cash flow,
// matching. Native queries do not see this restriction and filter deleted_at themselves.
@SQLRestriction("deleted_at IS NULL")
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

  // US-08-01: the reporting category - an annotation, not a financial field, so it stays mutable
  // under trg_transaction_append_only (FR-CAT-014: classifying never mutates the transaction).
  // How it was assigned is in transaction_categorization_log.
  @Column(name = "category_id", columnDefinition = UUID_COLUMN)
  private UUID categoryId;

  // A plain id, not an association: the shared security master is read separately, and a ledger
  // row must never cascade into it.
  @Column(name = "security_id", columnDefinition = UUID_COLUMN)
  private UUID securityId;

  @Column(precision = 28, scale = 10)
  private BigDecimal quantity;

  @Column(name = "unit_price", precision = 20, scale = 10)
  private BigDecimal unitPrice;

  @Column(name = "fee_amount", precision = 20, scale = 4)
  private BigDecimal feeAmount;

  @Column(name = "trade_date")
  private LocalDate tradeDate;

  @Column(name = "settlement_date")
  private LocalDate settlementDate;

  @Column(name = "gross_amount", precision = 20, scale = 4)
  private BigDecimal grossAmount;

  @Column(name = "tax_withheld_amount", precision = 20, scale = 4)
  private BigDecimal taxWithheldAmount;

  @Column(name = "net_amount", precision = 20, scale = 4)
  private BigDecimal netAmount;

  // US-07-02/FR-LIF-002: a voided original keeps every financial field and gains these three; its
  // reversing row (a new row, amounts negated) points back at it via replacesTransactionId. None of
  // the four is frozen by trg_transaction_append_only - they record a lifecycle, not a figure.
  @Column(name = "voided_at")
  private OffsetDateTime voidedAt;

  @Column(name = "voided_by", columnDefinition = UUID_COLUMN)
  private UUID voidedBy;

  @Column(name = "void_reason")
  private String voidReason;

  @Column(name = "replaces_transaction_id", columnDefinition = UUID_COLUMN)
  private UUID replacesTransactionId;

  // US-07-02/FR-LIF-002a T1: a soft-deleted manual row. The entity's @SQLRestriction hides it from
  // every JPA query; only TransactionRepository's native queries for restoring see it (V39).
  @Column(name = "deleted_at")
  private OffsetDateTime deletedAt;

  @Column(name = "deleted_by", columnDefinition = UUID_COLUMN)
  private UUID deletedBy;

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

  public UUID getCategoryId() {
    return categoryId;
  }

  public void setCategoryId(UUID categoryId) {
    this.categoryId = categoryId;
  }

  public UUID getSecurityId() {
    return securityId;
  }

  public void setSecurityId(UUID securityId) {
    this.securityId = securityId;
  }

  public BigDecimal getQuantity() {
    return quantity;
  }

  public void setQuantity(BigDecimal quantity) {
    this.quantity = quantity;
  }

  public BigDecimal getUnitPrice() {
    return unitPrice;
  }

  public void setUnitPrice(BigDecimal unitPrice) {
    this.unitPrice = unitPrice;
  }

  public BigDecimal getFeeAmount() {
    return feeAmount;
  }

  public void setFeeAmount(BigDecimal feeAmount) {
    this.feeAmount = feeAmount;
  }

  public LocalDate getTradeDate() {
    return tradeDate;
  }

  public void setTradeDate(LocalDate tradeDate) {
    this.tradeDate = tradeDate;
  }

  public LocalDate getSettlementDate() {
    return settlementDate;
  }

  public void setSettlementDate(LocalDate settlementDate) {
    this.settlementDate = settlementDate;
  }

  public BigDecimal getGrossAmount() {
    return grossAmount;
  }

  public void setGrossAmount(BigDecimal grossAmount) {
    this.grossAmount = grossAmount;
  }

  public BigDecimal getTaxWithheldAmount() {
    return taxWithheldAmount;
  }

  public void setTaxWithheldAmount(BigDecimal taxWithheldAmount) {
    this.taxWithheldAmount = taxWithheldAmount;
  }

  public BigDecimal getNetAmount() {
    return netAmount;
  }

  public void setNetAmount(BigDecimal netAmount) {
    this.netAmount = netAmount;
  }

  public OffsetDateTime getVoidedAt() {
    return voidedAt;
  }

  public void setVoidedAt(OffsetDateTime voidedAt) {
    this.voidedAt = voidedAt;
  }

  public UUID getVoidedBy() {
    return voidedBy;
  }

  public void setVoidedBy(UUID voidedBy) {
    this.voidedBy = voidedBy;
  }

  public String getVoidReason() {
    return voidReason;
  }

  public void setVoidReason(String voidReason) {
    this.voidReason = voidReason;
  }

  public UUID getReplacesTransactionId() {
    return replacesTransactionId;
  }

  public void setReplacesTransactionId(UUID replacesTransactionId) {
    this.replacesTransactionId = replacesTransactionId;
  }

  /** A reversing row of a void: it goes with its original and is never removed on its own. */
  public boolean isReversal() {
    return replacesTransactionId != null;
  }

  public OffsetDateTime getDeletedAt() {
    return deletedAt;
  }

  public void setDeletedAt(OffsetDateTime deletedAt) {
    this.deletedAt = deletedAt;
  }

  public UUID getDeletedBy() {
    return deletedBy;
  }

  public void setDeletedBy(UUID deletedBy) {
    this.deletedBy = deletedBy;
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
