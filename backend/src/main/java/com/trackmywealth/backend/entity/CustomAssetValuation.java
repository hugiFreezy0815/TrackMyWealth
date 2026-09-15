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
 * Maps {@code custom_asset_valuation} (V5) - a dated, manually entered valuation for a {@code
 * CUSTOM_ASSET} account (US-05-05, FR-NW-003). One row per {@code (account, valuationDate)} (V5's
 * own {@code UNIQUE} constraint); a correction is a new dated row, never an edit of an existing one
 * (RULE-028/PR-011 - no silent estimate ever looks the same as a reconciled one, and overwriting a
 * historical entry in place would erase which value was actually current for a past period).
 *
 * <p>Not an {@link AccountExtension}: this is a 1:many child of {@link Account}, not the 1:1
 * class-table-inheritance row {@code AccountExtension}'s subtypes are. V26's {@code
 * custom_asset_valuation_guard_1_type} trigger (reusing V5's generic {@code
 * trg_extension_type_guard}) still enforces that {@code account} is actually a {@code CUSTOM_ASSET}
 * account, and {@code custom_asset_valuation_guard_2_currency} enforces {@code currency} matches
 * the account's own {@code native_currency} - both at the DB level, translated to a clean 409 by
 * {@code GlobalExceptionHandler} rather than a raw 500, the same pattern this codebase uses for
 * every other DB-enforced invariant. V27 renamed both triggers (originally {@code
 * custom_asset_valuation_type_guard}/{@code custom_asset_valuation_currency_guard}) so the type
 * guard - the more fundamental check - fires first: PostgreSQL fires same-timing triggers in
 * alphabetical order by name, and the old names put the currency check first, so a row violating
 * both was always reported as a currency mismatch, masking the wrong-account-type problem.
 */
@Entity
@Table(name = "custom_asset_valuation")
public class CustomAssetValuation {

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  @Column(columnDefinition = "uuid")
  private UUID id;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "account_id", nullable = false)
  private Account account;

  @Column(name = "valuation_date", nullable = false)
  private LocalDate valuationDate;

  @Column(nullable = false)
  private BigDecimal value;

  @JdbcTypeCode(SqlTypes.CHAR)
  @Column(nullable = false, length = 3)
  private String currency;

  @Column(nullable = false)
  private String source = "MANUAL";

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

  public LocalDate getValuationDate() {
    return valuationDate;
  }

  public void setValuationDate(LocalDate valuationDate) {
    this.valuationDate = valuationDate;
  }

  public BigDecimal getValue() {
    return value;
  }

  public void setValue(BigDecimal value) {
    this.value = value;
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

  public OffsetDateTime getCreatedAt() {
    return createdAt;
  }
}
