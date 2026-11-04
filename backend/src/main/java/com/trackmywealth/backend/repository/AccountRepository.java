package com.trackmywealth.backend.repository;

import com.trackmywealth.backend.dto.AccountListFilter;
import com.trackmywealth.backend.entity.Account;
import jakarta.persistence.LockModeType;
import jakarta.persistence.criteria.Predicate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface AccountRepository
    extends JpaRepository<Account, UUID>, JpaSpecificationExecutor<Account> {

  /**
   * #380: the workspace's accounts matching {@code filter}, for the account list. The portfolio and
   * the workspace are fetched in the same query, so a row's portfolio name and the workspace's
   * currency cost nothing per account. The caller orders them.
   */
  static Specification<Account> listedBy(UUID workspaceId, AccountListFilter filter) {
    return (root, query, builder) -> {
      root.fetch("portfolio");
      root.fetch("workspace");
      List<Predicate> where = new ArrayList<>();
      where.add(builder.equal(root.get("workspace").get("id"), workspaceId));
      where.add(builder.equal(root.get("status"), filter.archived() ? "ARCHIVED" : "ACTIVE"));
      if (filter.portfolioId() != null) {
        where.add(builder.equal(root.get("portfolio").get("id"), filter.portfolioId()));
      }
      if (filter.accountType() != null) {
        where.add(builder.equal(root.get("accountType"), filter.accountType()));
      }
      return builder.and(where.toArray(Predicate[]::new));
    };
  }

  Optional<Account> findByWorkspaceIdAndIdentifierHash(UUID workspaceId, String identifierHash);

  Optional<Account> findByWorkspaceIdAndContractIdentifierHash(
      UUID workspaceId, String contractIdentifierHash);

  // Same SELECT ... FOR UPDATE pattern as AppUserRepository.findByIdForUpdate - serializes any
  // read-modify-write sequence keyed off this account (e.g. LoanRepaymentService, a card's
  // settlement detection) so a second concurrent writer blocks here until the first commits, rather
  // than both reading the same pre-change state and committing incompatible results neither one's
  // own single-request validation could have caught.
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("SELECT a FROM Account a WHERE a.id = :id")
  Optional<Account> findByIdForUpdate(@Param("id") UUID id);

  // US-07-22: the account's savings pocket as the database holds it now, not as an entity loaded
  // earlier in the transaction saw it - a commit that waited for the account's import lock must see
  // the pocket the commit before it created.
  @Query("SELECT a.savingsPocketAccountId FROM Account a WHERE a.id = :id")
  Optional<UUID> findSavingsPocketAccountId(@Param("id") UUID id);

  // US-07-22: links a new pocket by this column alone - the account entity never writes it (it is
  // not updatable), so an account entity loaded before the import lock cannot overwrite it. The
  // UPDATE still bumps the account's version (trg_bump_version, V4): the response shows the pocket,
  // so a member's older ETag is stale from then on. Only an account without a pocket gains one; the
  // caller checks that exactly one row changed.
  @Modifying(flushAutomatically = true)
  @Query(
      "UPDATE Account a SET a.savingsPocketAccountId = :pocketId"
          + " WHERE a.id = :id AND a.savingsPocketAccountId IS NULL")
  int linkSavingsPocket(@Param("id") UUID id, @Param("pocketId") UUID pocketId);

  // #325 follow-up: the member links another account as the pocket, replacing the one the import
  // created (archived, say). The caller holds the row's lock and has checked its version; the
  // trigger bumps it. Clears the persistence context so the account reads back afresh.
  @Modifying(flushAutomatically = true, clearAutomatically = true)
  @Query("UPDATE Account a SET a.savingsPocketAccountId = :pocketId WHERE a.id = :id")
  int relinkSavingsPocket(@Param("id") UUID id, @Param("pocketId") UUID pocketId);

  // #325 follow-up: whether an account other than accountId already has pocketId as its pocket.
  boolean existsBySavingsPocketAccountIdAndIdNot(UUID pocketId, UUID accountId);

  // US-04-03/FR-LIF-005: an institution summary counts only currently-active accounts - an
  // archived account is excluded from current totals (though still included in a historical
  // figure covering a period it was active in, not this story's concern). Explicit ordering
  // (oldest-created first), matching this codebase's own precedent for list-returning derived
  // queries (CustomAssetValuationRepository.findByAccountIdOrderByValuationDateDesc,
  // UserSessionRepository.findByUser_IdAndStatusOrderByLastSeenAtDesc) - without it, row order
  // isn't guaranteed stable across a table rewrite/vacuum/query-plan change, and a drillable list
  // reordering itself with no underlying data change is a worse experience than a fixed one.
  List<Account> findByPortfolioIdAndStatusOrderByCreatedAtAsc(UUID portfolioId, String status);

  // US-09-01: net worth spans every currently-active account in the workspace (FR-LIF-005 - an
  // archived account is excluded from current totals, same as the institution summary above).
  List<Account> findByWorkspaceIdAndStatusOrderByCreatedAtAsc(UUID workspaceId, String status);

  // US-09-02: spending spans every account in the workspace, archived ones included - a past
  // month's spending on an account archived since must still count (FR-LIF-005 only excludes an
  // archived account from *current* totals).
  List<Account> findByWorkspaceId(UUID workspaceId);
}
