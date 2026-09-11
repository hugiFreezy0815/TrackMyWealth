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
import java.time.OffsetDateTime;
import java.util.UUID;
import org.hibernate.annotations.Generated;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.generator.EventType;
import org.hibernate.type.SqlTypes;

/**
 * Maps {@code account} (V4) - the class-table-inheritance parent for every account type (DM-17,
 * FR-ACC-005). {@code nature} is a DB {@code GENERATED ALWAYS AS} column derived from {@code
 * account_type} (DB-12) - never settable by application code, hence {@code insertable = false,
 * updatable = false} like the audit columns. The capability flags ({@code holdsPositions} etc.,
 * FR-ACC-010/011) are what every other layer of the app must branch on instead of {@code
 * accountType} (US-05-04) - {@link com.trackmywealth.backend.service.AccountService} is the one
 * place {@code accountType} itself drives behaviour, translating it into these flags and into which
 * extension entity (if any) gets a row in the same transaction.
 */
@Entity
@Table(name = "account")
public class Account {

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  @Column(columnDefinition = "uuid")
  private UUID id;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "workspace_id", nullable = false)
  private Workspace workspace;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "financial_institution_id", nullable = false)
  private FinancialInstitution financialInstitution;

  @Column(name = "account_type", nullable = false)
  private String accountType;

  @Column(nullable = false)
  private String name;

  // V4 declares this CHAR(3) (fixed-width ISO 4217 code), not varchar.
  @JdbcTypeCode(SqlTypes.CHAR)
  @Column(name = "native_currency", nullable = false, length = 3)
  private String nativeCurrency;

  // DB-12: GENERATED ALWAYS AS (CASE account_type ...) STORED - read-only from Java's side.
  @Generated(event = {EventType.INSERT, EventType.UPDATE})
  @Column(insertable = false, updatable = false)
  private String nature;

  @Column(name = "holds_positions", nullable = false)
  private boolean holdsPositions;

  @Column(name = "has_transactions", nullable = false)
  private boolean hasTransactions = true;

  @Column(name = "has_statement_cycle", nullable = false)
  private boolean hasStatementCycle;

  @Column(name = "has_amortisation", nullable = false)
  private boolean hasAmortisation;

  @Column(name = "has_contribution_limit", nullable = false)
  private boolean hasContributionLimit;

  @Column(name = "is_discretionary", nullable = false)
  private boolean discretionary;

  @Column(name = "manual_valuation", nullable = false)
  private boolean manualValuation;

  @Column(nullable = false)
  private String status = "ACTIVE";

  @Generated(event = EventType.INSERT)
  @Column(name = "created_at", insertable = false, updatable = false)
  private OffsetDateTime createdAt;

  @Generated(event = {EventType.INSERT, EventType.UPDATE})
  @Column(name = "updated_at", insertable = false, updatable = false)
  private OffsetDateTime updatedAt;

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

  public FinancialInstitution getFinancialInstitution() {
    return financialInstitution;
  }

  public void setFinancialInstitution(FinancialInstitution financialInstitution) {
    this.financialInstitution = financialInstitution;
  }

  public String getAccountType() {
    return accountType;
  }

  public void setAccountType(String accountType) {
    this.accountType = accountType;
  }

  public String getName() {
    return name;
  }

  public void setName(String name) {
    this.name = name;
  }

  public String getNativeCurrency() {
    return nativeCurrency;
  }

  public void setNativeCurrency(String nativeCurrency) {
    this.nativeCurrency = nativeCurrency;
  }

  public String getNature() {
    return nature;
  }

  public boolean isHoldsPositions() {
    return holdsPositions;
  }

  public void setHoldsPositions(boolean holdsPositions) {
    this.holdsPositions = holdsPositions;
  }

  public boolean isHasTransactions() {
    return hasTransactions;
  }

  public void setHasTransactions(boolean hasTransactions) {
    this.hasTransactions = hasTransactions;
  }

  public boolean isHasStatementCycle() {
    return hasStatementCycle;
  }

  public void setHasStatementCycle(boolean hasStatementCycle) {
    this.hasStatementCycle = hasStatementCycle;
  }

  public boolean isHasAmortisation() {
    return hasAmortisation;
  }

  public void setHasAmortisation(boolean hasAmortisation) {
    this.hasAmortisation = hasAmortisation;
  }

  public boolean isHasContributionLimit() {
    return hasContributionLimit;
  }

  public void setHasContributionLimit(boolean hasContributionLimit) {
    this.hasContributionLimit = hasContributionLimit;
  }

  public boolean isDiscretionary() {
    return discretionary;
  }

  public void setDiscretionary(boolean discretionary) {
    this.discretionary = discretionary;
  }

  public boolean isManualValuation() {
    return manualValuation;
  }

  public void setManualValuation(boolean manualValuation) {
    this.manualValuation = manualValuation;
  }

  public String getStatus() {
    return status;
  }

  public void setStatus(String status) {
    this.status = status;
  }

  public OffsetDateTime getCreatedAt() {
    return createdAt;
  }

  public OffsetDateTime getUpdatedAt() {
    return updatedAt;
  }

  public Integer getVersion() {
    return version;
  }
}
