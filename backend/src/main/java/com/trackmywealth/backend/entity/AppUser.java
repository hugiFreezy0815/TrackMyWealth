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
 * Maps {@code app_user} (V2, {@code token_version} added by V16) - a person who can authenticate.
 * Deliberately separate from {@link HouseholdMember} (RULE-018): {@code role} here governs
 * administration rights only, never financial-data access (FR-USR-010, FR-TEN-007).
 */
@Entity
@Table(name = "app_user")
public class AppUser {

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  @Column(columnDefinition = "uuid")
  private UUID id;

  // V2 declares this citext (case-insensitive equality at the database level, FR-USR-*) rather
  // than a plain varchar - the columnDefinition hint is what makes Hibernate's schema validator
  // (ddl-auto: validate) agree with the actual column type instead of expecting varchar(255).
  @Column(nullable = false, columnDefinition = "citext")
  private String email;

  @Column(name = "password_hash", nullable = false)
  private String passwordHash;

  @Column(nullable = false)
  private String role = "STANDARD_USER";

  @Column(nullable = false)
  private String status = "ACTIVE";

  @Column(nullable = false)
  private String language = "EN";

  // V2 declares this CHAR(3) (fixed-width ISO 4217 code), not varchar.
  @JdbcTypeCode(SqlTypes.CHAR)
  @Column(name = "reporting_currency", nullable = false, length = 3)
  private String reportingCurrency = "CHF";

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "household_member_id")
  private HouseholdMember householdMember;

  @Column(name = "mfa_totp_secret")
  private String mfaTotpSecret;

  @Column(name = "mfa_enabled", nullable = false)
  private boolean mfaEnabled;

  @Column(name = "failed_login_count", nullable = false)
  private int failedLoginCount;

  @Column(name = "locked_until")
  private OffsetDateTime lockedUntil;

  @Column(name = "last_login_at")
  private OffsetDateTime lastLoginAt;

  // FR-AUT-005: incrementing this invalidates every previously issued access token immediately,
  // independent of that token's own expiry. Not touched by this story (no token issuance path yet
  // needs to bump it), but must round-trip correctly since it has a real default at the DB level.
  @Column(name = "token_version", nullable = false)
  private int tokenVersion;

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

  public String getEmail() {
    return email;
  }

  public void setEmail(String email) {
    this.email = email;
  }

  public String getPasswordHash() {
    return passwordHash;
  }

  public void setPasswordHash(String passwordHash) {
    this.passwordHash = passwordHash;
  }

  public String getRole() {
    return role;
  }

  public void setRole(String role) {
    this.role = role;
  }

  public String getStatus() {
    return status;
  }

  public void setStatus(String status) {
    this.status = status;
  }

  public String getLanguage() {
    return language;
  }

  public void setLanguage(String language) {
    this.language = language;
  }

  public String getReportingCurrency() {
    return reportingCurrency;
  }

  public void setReportingCurrency(String reportingCurrency) {
    this.reportingCurrency = reportingCurrency;
  }

  public HouseholdMember getHouseholdMember() {
    return householdMember;
  }

  public void setHouseholdMember(HouseholdMember householdMember) {
    this.householdMember = householdMember;
  }

  public boolean isMfaEnabled() {
    return mfaEnabled;
  }

  public int getFailedLoginCount() {
    return failedLoginCount;
  }

  public void setFailedLoginCount(int failedLoginCount) {
    this.failedLoginCount = failedLoginCount;
  }

  public OffsetDateTime getLockedUntil() {
    return lockedUntil;
  }

  public void setLockedUntil(OffsetDateTime lockedUntil) {
    this.lockedUntil = lockedUntil;
  }

  public OffsetDateTime getLastLoginAt() {
    return lastLoginAt;
  }

  public void setLastLoginAt(OffsetDateTime lastLoginAt) {
    this.lastLoginAt = lastLoginAt;
  }

  public int getTokenVersion() {
    return tokenVersion;
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
