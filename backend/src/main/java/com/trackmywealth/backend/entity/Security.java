package com.trackmywealth.backend.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Maps {@code security} (V7): one global, shared master record per instrument (DM-25). It carries
 * no workspace or user id by design (NFR-LIC-007) and is <b>read-only here</b> - rows are inserted
 * only through {@code SecurityRepository#insertIfAbsent} (a race-safe {@code ON CONFLICT} upsert)
 * and never edited by a caller: per-workspace changes are overrides (US-12-04), not edits of the
 * shared row.
 */
@Entity
@Table(name = "security")
public class Security {

  @Id
  @Column(columnDefinition = "uuid")
  private UUID id;

  @JdbcTypeCode(SqlTypes.CHAR)
  @Column(length = 12, insertable = false, updatable = false)
  private String isin;

  @Column(name = "synthetic_key", insertable = false, updatable = false)
  private String syntheticKey;

  @Column(name = "legal_name", insertable = false, updatable = false)
  private String legalName;

  @Column(name = "display_name", insertable = false, updatable = false)
  private String displayName;

  @Column(name = "instrument_type", insertable = false, updatable = false)
  private String instrumentType;

  @JdbcTypeCode(SqlTypes.CHAR)
  @Column(name = "security_country", length = 2, insertable = false, updatable = false)
  private String securityCountry;

  @JdbcTypeCode(SqlTypes.CHAR)
  @Column(name = "issuer_country", length = 2, insertable = false, updatable = false)
  private String issuerCountry;

  @JdbcTypeCode(SqlTypes.CHAR)
  @Column(name = "denomination_currency", length = 3, insertable = false, updatable = false)
  private String denominationCurrency;

  @JdbcTypeCode(SqlTypes.CHAR)
  @Column(name = "gics_sub_industry_code", length = 8, insertable = false, updatable = false)
  private String gicsSubIndustryCode;

  @Column(name = "state", insertable = false, updatable = false)
  private String state;

  public UUID getId() {
    return id;
  }

  public String getIsin() {
    return isin;
  }

  public String getSyntheticKey() {
    return syntheticKey;
  }

  public String getLegalName() {
    return legalName;
  }

  public String getDisplayName() {
    return displayName;
  }

  public String getInstrumentType() {
    return instrumentType;
  }

  public String getSecurityCountry() {
    return securityCountry;
  }

  public String getIssuerCountry() {
    return issuerCountry;
  }

  public String getDenominationCurrency() {
    return denominationCurrency;
  }

  public String getGicsSubIndustryCode() {
    return gicsSubIndustryCode;
  }

  public String getState() {
    return state;
  }
}
