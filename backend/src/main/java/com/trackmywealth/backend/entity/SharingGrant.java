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
 * Maps {@code sharing_grant} (V6) - explicit, revocable, granular access from one {@link
 * WorkspaceMember} to another over an {@link Account}, a {@link FinancialInstitution}, or the whole
 * {@link Workspace} (US-03-03, FR-TEN-002/008/009). Distinct from {@link AccountOwnership} (who
 * owns value) - this expresses who else may view or edit a resource they do not own.
 *
 * <p>Exactly one of {@code scopeAccount}/{@code scopeInstitution} is set, matching {@code
 * scopeType} - mirrored by V6's own {@code CHECK} constraint at the DB level; {@link
 * com.trackmywealth.backend.service.SharingGrantService} validates the combination before this is
 * ever persisted, the same "validate before insert" ordering {@code AccountService} uses.
 *
 * <p>{@code revokedAt} is null while the grant is active; setting it takes effect on the very next
 * request that consults this row (FR-TEN-009) - there is no separate "deleted" state, since a
 * revoked grant is still meaningful audit history of who granted/revoked what and when.
 */
@Entity
@Table(name = "sharing_grant")
public class SharingGrant {

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  @Column(columnDefinition = "uuid")
  private UUID id;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "workspace_id", nullable = false)
  private Workspace workspace;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "granted_to_member_id", nullable = false)
  private WorkspaceMember grantedToMember;

  @Column(name = "scope_type", nullable = false)
  private String scopeType;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "scope_account_id")
  private Account scopeAccount;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "scope_institution_id")
  private FinancialInstitution scopeInstitution;

  @Column(name = "access_level", nullable = false)
  private String accessLevel;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "granted_by_member_id", nullable = false)
  private WorkspaceMember grantedByMember;

  @Generated(event = EventType.INSERT)
  @Column(name = "granted_at", insertable = false, updatable = false)
  private OffsetDateTime grantedAt;

  @Column(name = "revoked_at")
  private OffsetDateTime revokedAt;

  public UUID getId() {
    return id;
  }

  public Workspace getWorkspace() {
    return workspace;
  }

  public void setWorkspace(Workspace workspace) {
    this.workspace = workspace;
  }

  public WorkspaceMember getGrantedToMember() {
    return grantedToMember;
  }

  public void setGrantedToMember(WorkspaceMember grantedToMember) {
    this.grantedToMember = grantedToMember;
  }

  public String getScopeType() {
    return scopeType;
  }

  public void setScopeType(String scopeType) {
    this.scopeType = scopeType;
  }

  public Account getScopeAccount() {
    return scopeAccount;
  }

  public void setScopeAccount(Account scopeAccount) {
    this.scopeAccount = scopeAccount;
  }

  public FinancialInstitution getScopeInstitution() {
    return scopeInstitution;
  }

  public void setScopeInstitution(FinancialInstitution scopeInstitution) {
    this.scopeInstitution = scopeInstitution;
  }

  public String getAccessLevel() {
    return accessLevel;
  }

  public void setAccessLevel(String accessLevel) {
    this.accessLevel = accessLevel;
  }

  public WorkspaceMember getGrantedByMember() {
    return grantedByMember;
  }

  public void setGrantedByMember(WorkspaceMember grantedByMember) {
    this.grantedByMember = grantedByMember;
  }

  public OffsetDateTime getGrantedAt() {
    return grantedAt;
  }

  public OffsetDateTime getRevokedAt() {
    return revokedAt;
  }

  public void setRevokedAt(OffsetDateTime revokedAt) {
    this.revokedAt = revokedAt;
  }
}
