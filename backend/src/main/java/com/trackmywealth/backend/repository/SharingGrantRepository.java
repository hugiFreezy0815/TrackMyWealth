package com.trackmywealth.backend.repository;

import com.trackmywealth.backend.entity.SharingGrant;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface SharingGrantRepository extends JpaRepository<SharingGrant, UUID> {

  // Same SELECT ... FOR UPDATE pattern as AccountRepository/AppUserRepository's own
  // findByIdForUpdate - serializes revoke()'s read-check-write sequence so a second concurrent
  // revoke blocks here until the first commits and then observes revokedAt already set, rather
  // than both reading revokedAt == null and both succeeding (the exact bug class already fixed
  // for AccountOwnershipService in commit ccc7f6b).
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("SELECT g FROM SharingGrant g WHERE g.id = :id")
  Optional<SharingGrant> findByIdForUpdate(@Param("id") UUID id);

  // One query serves all three scope checks (AccessControlService.effectiveAccessLevel):
  // - Account-level check: pass the account's own id and its institution's id.
  // - Institution-level check: pass a null accountId and the institution's id.
  // - Workspace-level check: pass null for both - only the WORKSPACE clause can then match.
  // A WORKSPACE-scope grant always applies (broadest); an INSTITUTION-scope grant applies to
  // every account under it; an ACCOUNT-scope grant applies to that one account only - the same
  // scope hierarchy the story itself describes ("one account, one institution, or the whole
  // workspace"). Comparing a null :accountId/:institutionId against scope_account_id/
  // scope_institution_id via `=` is always false in JPQL/SQL, never a false positive, so passing
  // null simply disables that clause rather than needing a separate query per scope level.
  @Query(
      "SELECT g FROM SharingGrant g WHERE g.grantedToMember.id = :memberId AND g.revokedAt IS NULL"
          + " AND (g.scopeType = 'WORKSPACE'"
          + " OR (g.scopeType = 'INSTITUTION' AND g.scopeInstitution.id = :institutionId)"
          + " OR (g.scopeType = 'ACCOUNT' AND g.scopeAccount.id = :accountId))")
  List<SharingGrant> findApplicableGrants(
      @Param("memberId") UUID memberId,
      @Param("accountId") UUID accountId,
      @Param("institutionId") UUID institutionId);

  // #122's SYSTEM_ADMINISTRATOR-bootstrap exception (SharingGrantService#isBootstrapping): whether
  // this scope already has a non-revoked grant at all, regardless of who it was granted to or by.
  // Re-checked on every grant() call, not cached/flagged - if every grant of a scope is later
  // revoked, these both answer false again and the exception re-arms, so the scope can never
  // permanently relock itself the way #122 originally did.
  boolean existsByScopeInstitutionIdAndRevokedAtIsNull(UUID scopeInstitutionId);

  // WORKSPACE-scope grants carry no explicit workspace id of their own - RLS already confines every
  // row in this table to the caller's workspace (same as findApplicableGrants above), so scopeType
  // alone is enough to ask "does this workspace have a non-revoked WORKSPACE-scope grant".
  boolean existsByScopeTypeAndRevokedAtIsNull(String scopeType);

  // The revoke() counterpart (SharingGrantService#isSoleRemainingGrant): whether any *other*
  // non-revoked grant of this scope exists besides the one being revoked (excludeId). A
  // SYSTEM_ADMINISTRATOR who bootstrapped a scope's only grant - typically to someone else, not
  // themselves - would otherwise have no standing to revoke it at all (a mistaken bootstrap grant
  // would be permanent), so revoke() gets the same narrow exception grant() does, gated on "this is
  // the last one" rather than "none exist yet".
  boolean existsByScopeInstitutionIdAndRevokedAtIsNullAndIdNot(
      UUID scopeInstitutionId, UUID excludeId);

  boolean existsByScopeTypeAndRevokedAtIsNullAndIdNot(String scopeType, UUID excludeId);
}
