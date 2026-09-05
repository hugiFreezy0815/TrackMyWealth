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

/** Maps {@code user_session} (V16) - a listable, individually-revocable login (FR-AUT-002). */
@Entity
@Table(name = "user_session")
public class UserSession {

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  @Column(columnDefinition = "uuid")
  private UUID id;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "user_id", nullable = false)
  private AppUser user;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "refresh_token_id")
  private RefreshToken refreshToken;

  @Column(nullable = false)
  private String status = "ACTIVE";

  @Column(name = "device_label")
  private String deviceLabel;

  // NFR-OPS-005: no raw client identifiers in observability data - callers must pass an
  // already-hashed value, never a raw IP address.
  @Column(name = "ip_address_hash")
  private String ipAddressHash;

  @Generated(event = EventType.INSERT)
  @Column(name = "created_at", insertable = false, updatable = false)
  private OffsetDateTime createdAt;

  @Column(name = "last_seen_at", nullable = false)
  private OffsetDateTime lastSeenAt;

  @Column(name = "revoked_at")
  private OffsetDateTime revokedAt;

  public UUID getId() {
    return id;
  }

  public AppUser getUser() {
    return user;
  }

  public void setUser(AppUser user) {
    this.user = user;
  }

  public RefreshToken getRefreshToken() {
    return refreshToken;
  }

  public void setRefreshToken(RefreshToken refreshToken) {
    this.refreshToken = refreshToken;
  }

  public String getStatus() {
    return status;
  }

  public void setStatus(String status) {
    this.status = status;
  }

  public String getDeviceLabel() {
    return deviceLabel;
  }

  public void setDeviceLabel(String deviceLabel) {
    this.deviceLabel = deviceLabel;
  }

  public String getIpAddressHash() {
    return ipAddressHash;
  }

  public void setIpAddressHash(String ipAddressHash) {
    this.ipAddressHash = ipAddressHash;
  }

  public OffsetDateTime getCreatedAt() {
    return createdAt;
  }

  public OffsetDateTime getLastSeenAt() {
    return lastSeenAt;
  }

  public void setLastSeenAt(OffsetDateTime lastSeenAt) {
    this.lastSeenAt = lastSeenAt;
  }

  public OffsetDateTime getRevokedAt() {
    return revokedAt;
  }
}
