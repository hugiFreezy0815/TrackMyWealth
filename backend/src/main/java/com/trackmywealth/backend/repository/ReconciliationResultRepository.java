package com.trackmywealth.backend.repository;

import com.trackmywealth.backend.entity.ReconciliationResult;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface ReconciliationResultRepository extends JpaRepository<ReconciliationResult, UUID> {

  Optional<ReconciliationResult> findBySnapshotIdAndAffectedSecurityIdIsNull(UUID snapshotId);

  /** Every result compared against the snapshot, any scope (US-07-12, a batch's rollback). */
  List<ReconciliationResult> findBySnapshotId(UUID snapshotId);

  /**
   * Whether any transaction, soft-deleted ones included, is the adjusting entry of one of {@code
   * resultIds} (V63): such a result can never be deleted, as the ledger is append-only. Native SQL,
   * because the transaction entity's restriction hides a withdrawn (soft-deleted) adjustment.
   */
  @Query(
      value =
          "SELECT EXISTS (SELECT 1 FROM transaction"
              + " WHERE reconciliation_result_id IN (:resultIds))",
      nativeQuery = true)
  boolean existsAdjustmentOf(@Param("resultIds") Collection<UUID> resultIds);

  List<ReconciliationResult> findByAccountIdAndStatusAndAffectedSecurityIdIsNull(
      UUID accountId, String status);

  boolean existsByAccountIdAndStatusAndAffectedSecurityIdIsNull(UUID accountId, String status);

  /** Whether the account has the cash-scope result {@code id}, without loading it (US-25-03). */
  boolean existsByIdAndAccountIdAndAffectedSecurityIdIsNull(UUID id, UUID accountId);

  Page<ReconciliationResult> findByAccountIdAndAffectedSecurityIdIsNullOrderByCreatedAtDesc(
      UUID accountId, Pageable pageable);

  /** Account ids carrying a cash-scope result in {@code status}, for batched headline warnings. */
  @Query(
      "select distinct r.account.id from ReconciliationResult r"
          + " where r.account.id in :accountIds"
          + " and r.affectedSecurityId is null and r.status = :status")
  List<UUID> findAccountIdsWithCashResultInStatus(
      @Param("accountIds") Collection<UUID> accountIds, @Param("status") String status);
}
