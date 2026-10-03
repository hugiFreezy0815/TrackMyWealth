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

  List<ReconciliationResult> findByAccountIdAndStatusAndAffectedSecurityIdIsNull(
      UUID accountId, String status);

  boolean existsByAccountIdAndStatusAndAffectedSecurityIdIsNull(UUID accountId, String status);

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
