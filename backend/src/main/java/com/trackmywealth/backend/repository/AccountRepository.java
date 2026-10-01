package com.trackmywealth.backend.repository;

import com.trackmywealth.backend.entity.Account;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface AccountRepository extends JpaRepository<Account, UUID> {

  // Same SELECT ... FOR UPDATE pattern as AppUserRepository.findByIdForUpdate - serializes any
  // read-modify-write sequence keyed off this account (see AccountOwnershipService.assignOwnership,
  // the first caller) so a second concurrent writer blocks here until the first commits, rather
  // than both reading the same pre-change state and committing incompatible results neither one's
  // own single-request validation could have caught.
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("SELECT a FROM Account a WHERE a.id = :id")
  Optional<Account> findByIdForUpdate(@Param("id") UUID id);

  // US-04-03/FR-LIF-005: an institution summary counts only currently-active accounts - an
  // archived account is excluded from current totals (though still included in a historical
  // figure covering a period it was active in, not this story's concern). Explicit ordering
  // (oldest-created first), matching this codebase's own precedent for list-returning derived
  // queries (CustomAssetValuationRepository.findByAccountIdOrderByValuationDateDesc,
  // UserSessionRepository.findByUser_IdAndStatusOrderByLastSeenAtDesc) - without it, row order
  // isn't guaranteed stable across a table rewrite/vacuum/query-plan change, and a drillable list
  // reordering itself with no underlying data change is a worse experience than a fixed one.
  List<Account> findByFinancialInstitutionIdAndStatusOrderByCreatedAtAsc(
      UUID financialInstitutionId, String status);

  // US-09-01: net worth spans every currently-active account in the workspace (FR-LIF-005 - an
  // archived account is excluded from current totals, same as the institution summary above).
  List<Account> findByWorkspaceIdAndStatusOrderByCreatedAtAsc(UUID workspaceId, String status);

  // US-09-02: spending spans every account in the workspace, archived ones included - a past
  // month's spending on an account archived since must still count (FR-LIF-005 only excludes an
  // archived account from *current* totals).
  List<Account> findByWorkspaceId(UUID workspaceId);

  /**
   * #207: ownership is a full-replacement aggregate whose concurrency token is the parent account.
   * The ownership rows themselves are dated history and there may legitimately be zero current
   * rows, so no child row can carry the aggregate revision. This UPDATE changes no column itself;
   * its BEFORE UPDATE triggers advance the account's database-owned version (trg_bump_version) and,
   * as a side effect, set {@code updated_at} to now (trg_set_updated_at) - an ownership change
   * counts as a change of the account (ADR 0004).
   */
  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query(value = "UPDATE account SET updated_at = updated_at WHERE id = :id", nativeQuery = true)
  int bumpOwnershipAggregateVersion(@Param("id") UUID id);
}
