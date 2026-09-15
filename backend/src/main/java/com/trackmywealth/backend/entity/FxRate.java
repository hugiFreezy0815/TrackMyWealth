package com.trackmywealth.backend.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
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
 * Maps {@code fx_rate} (V8) - a dated parallel series to {@link
 * com.trackmywealth.backend.entity.Account} currency prices, under the same provenance rules
 * (FR-PRC-013, US-06-01). A row's {@code rate} converts an amount in {@code baseCurrency} to {@code
 * quoteCurrency} (multiply by {@code rate}); the DB's {@code UNIQUE(base_currency, quote_currency,
 * rate_date, source)} constraint - not this entity - is what prevents duplicate rows for the same
 * pair/date/source (US-06-01 AC#2).
 *
 * <p>{@code id} is mapped as the sole {@code @Id} even though the table's actual primary key is the
 * composite {@code (id, rate_date)} required by range partitioning: {@code id} alone is already
 * globally unique (DB-generated via {@code gen_random_uuid()}), so every write path this entity is
 * used for stays correct without Hibernate needing to know about the composite key - see {@code
 * docs/architecture/database-schema.md} §5.
 *
 * <p>Global reference data, not tenant data: no {@code workspace_id}, no row-level security (see
 * {@code CLAUDE.md}'s multi-tenancy section).
 */
@Entity
@Table(name = "fx_rate")
public class FxRate {

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  @Column(columnDefinition = "uuid")
  private UUID id;

  @JdbcTypeCode(SqlTypes.CHAR)
  @Column(name = "base_currency", nullable = false, length = 3)
  private String baseCurrency;

  @JdbcTypeCode(SqlTypes.CHAR)
  @Column(name = "quote_currency", nullable = false, length = 3)
  private String quoteCurrency;

  @Column(name = "rate_date", nullable = false)
  private LocalDate rateDate;

  @Column(nullable = false)
  private BigDecimal rate;

  @Column(nullable = false)
  private String source;

  @Generated(event = EventType.INSERT)
  @Column(name = "retrieved_at", insertable = false, updatable = false)
  private OffsetDateTime retrievedAt;

  public UUID getId() {
    return id;
  }

  public String getBaseCurrency() {
    return baseCurrency;
  }

  public void setBaseCurrency(String baseCurrency) {
    this.baseCurrency = baseCurrency;
  }

  public String getQuoteCurrency() {
    return quoteCurrency;
  }

  public void setQuoteCurrency(String quoteCurrency) {
    this.quoteCurrency = quoteCurrency;
  }

  public LocalDate getRateDate() {
    return rateDate;
  }

  public void setRateDate(LocalDate rateDate) {
    this.rateDate = rateDate;
  }

  public BigDecimal getRate() {
    return rate;
  }

  public void setRate(BigDecimal rate) {
    this.rate = rate;
  }

  public String getSource() {
    return source;
  }

  public void setSource(String source) {
    this.source = source;
  }

  public OffsetDateTime getRetrievedAt() {
    return retrievedAt;
  }
}
