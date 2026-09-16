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
}
