package com.trackmywealth.backend.repository;

import com.trackmywealth.backend.entity.ImportBatch;
import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/**
 * US-07-04. Every lookup names the account (and so its workspace): the application connects as a
 * role that may bypass RLS in some deployments, and a batch id in another account's path is
 * answered like an unknown one.
 */
@Repository
public interface ImportBatchRepository extends JpaRepository<ImportBatch, UUID> {

  Optional<ImportBatch> findByIdAndAccountId(UUID id, UUID accountId);

  /**
   * The batch, locked until the transaction ends: parse, row inclusion, commit and discard queue
   * behind each other, so a retried commit sees the first one's {@code COMMITTED}.
   */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("SELECT b FROM ImportBatch b WHERE b.id = :id AND b.accountId = :accountId")
  Optional<ImportBatch> findForUpdate(@Param("id") UUID id, @Param("accountId") UUID accountId);

  /**
   * Moves the batch's version on without changing a column ({@code trg_bump_version}): a change to
   * one of its rows changes what a commit confirms.
   */
  @Modifying(flushAutomatically = true)
  @Query(value = "UPDATE import_batch SET version = version WHERE id = :id", nativeQuery = true)
  int touch(@Param("id") UUID id);

  // The caller fixes the order (newest first): a Pageable's sort is client input.
  Page<ImportBatch> findByAccountId(UUID accountId, Pageable pageable);

  /**
   * The account's batches in {@code status} of any of these files, latest commit first: the
   * same-file warning of a whole page of batches in one query.
   */
  List<ImportBatch> findByAccountIdAndStatusAndFileSha256InOrderByCommittedAtDesc(
      UUID accountId, String status, Collection<String> fileSha256s);

  /** How many of the account's batches are in one of {@code statuses}. */
  long countByAccountIdAndStatusIn(UUID accountId, Collection<String> statuses);
}
