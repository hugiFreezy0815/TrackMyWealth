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
import java.time.OffsetDateTime;
import java.util.UUID;
import org.hibernate.annotations.Generated;
import org.hibernate.generator.EventType;

/**
 * Maps {@code refresh_token} (V16). The plaintext token is never stored - only {@code tokenHash} -
 * and is returned to the caller exactly once, at issuance (FR-AUT-004).
 */
@Entity
@Table(name = "refresh_token")
public class RefreshToken {

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  @Column(columnDefinition = "uuid")
  private UUID id;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "user_id", nullable = false)
  private AppUser user;

  // FR-AUT-004: rotation family - reuse of any already-rotated-away token in this family
  // invalidates every token in it and is treated as a suspected theft event. A freshly issued
  // token (this story never rotates one) starts its own new family.
  @Column(name = "family_id", nullable = false, columnDefinition = "uuid")
  private UUID familyId;

  @Column(name = "token_hash", nullable = false)
  private String tokenHash;

  @Column(name = "device_label")
  private String deviceLabel;

  @Generated(event = EventType.INSERT)
  @Column(name = "issued_at", insertable = false, updatable = false)
  private OffsetDateTime issuedAt;

  @Column(name = "expires_at", nullable = false)
  private OffsetDateTime expiresAt;

  @Column(name = "revoked_at")
  private OffsetDateTime revokedAt;

  // Set together with revokedAt, only ever by markRotatedOutBy() below - the rotation chain
  // (US-02-02) that lets a family be walked forward from any of its tokens, distinct from
  // familyId which only says "these all belong together," not "in what order."
  @Column(name = "replaced_by_token_id", columnDefinition = "uuid")
  private UUID replacedByTokenId;

  @Column(name = "theft_suspected", nullable = false)
  private boolean theftSuspected;

  public UUID getId() {
    return id;
  }

  public AppUser getUser() {
    return user;
  }

  public void setUser(AppUser user) {
    this.user = user;
  }

  public UUID getFamilyId() {
    return familyId;
  }

  public void setFamilyId(UUID familyId) {
    this.familyId = familyId;
  }

  public String getTokenHash() {
    return tokenHash;
  }

  public void setTokenHash(String tokenHash) {
    this.tokenHash = tokenHash;
  }

  public String getDeviceLabel() {
    return deviceLabel;
  }

  public void setDeviceLabel(String deviceLabel) {
    this.deviceLabel = deviceLabel;
  }

  public OffsetDateTime getIssuedAt() {
    return issuedAt;
  }

  public OffsetDateTime getExpiresAt() {
    return expiresAt;
  }

  public void setExpiresAt(OffsetDateTime expiresAt) {
    this.expiresAt = expiresAt;
  }

  public OffsetDateTime getRevokedAt() {
    return revokedAt;
  }

  public UUID getReplacedByTokenId() {
    return replacedByTokenId;
  }

  // US-02-02: the only way a token is ever revoked as an individual entity (as opposed to the
  // bulk revocations in RefreshTokenRepository) - rotation always marks both fields together,
  // since one without the other would either leave a dangling chain pointer or a "revoked but
  // still the session's current token" contradiction.
  public void markRotatedOutBy(UUID replacementTokenId, OffsetDateTime revokedAt) {
    this.replacedByTokenId = replacementTokenId;
    this.revokedAt = revokedAt;
  }

  public boolean isTheftSuspected() {
    return theftSuspected;
  }
}
