package com.trackmywealth.backend.repository;

import com.trackmywealth.backend.dto.ReportedHoldingValue;
import com.trackmywealth.backend.entity.AccountSnapshot;
import jakarta.persistence.LockModeType;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface AccountSnapshotRepository extends JpaRepository<AccountSnapshot, UUID> {

  Page<AccountSnapshot> findByAccountIdAndWithdrawnAtIsNull(UUID accountId, Pageable pageable);

  Optional<AccountSnapshot> findByIdAndAccountId(UUID id, UUID accountId);

  Optional<AccountSnapshot> findByAccountIdAndSnapshotDateAndSourceAndWithdrawnAtIsNull(
      UUID accountId, LocalDate snapshotDate, String source);

  /**
   * US-15-05: newest non-withdrawn snapshot that actually contains holdings. A newer balance-only
   * snapshot must not hide these positions. The caller requests one row with PageRequest.of(0, 1).
   * Sort mirrors the portfolio's positionsAsOf source (#390).
   */
  @Query(
      "SELECT s FROM AccountSnapshot s WHERE s.account.id = :accountId"
          + " AND s.withdrawnAt IS NULL AND s.snapshotDate <= :asOf"
          + " AND EXISTS (SELECT h.id FROM SnapshotHolding h WHERE h.snapshotId = s.id)"
          + " ORDER BY s.snapshotDate DESC, s.createdAt DESC, s.id DESC")
  List<AccountSnapshot> findNewestWithHoldings(
      @Param("accountId") UUID accountId, @Param("asOf") LocalDate asOf, Pageable pageable);

  /**
   * Read the latest statement and all its components in one query. Older imported statements with
   * cash that could not be safely backfilled remain incomplete. A statement that reports cash lists
   * every currency the depot holds, so a snapshot with foreign cash but no own-currency balance
   * holds none of the account currency (0); one without any cash stays incomplete (#614).
   */
  @Query(
      value =
          """
          WITH latest AS (
            SELECT DISTINCT ON (s.account_id) s.id, s.account_id, s.snapshot_date, s.balance,
                   s.currency, s.balance_import_batch_id
            FROM account_snapshot s
            WHERE s.account_id IN (:accountIds)
              AND s.snapshot_date <= :asOf
              AND s.withdrawn_at IS NULL
              AND EXISTS (SELECT 1 FROM snapshot_holding h WHERE h.snapshot_id = s.id)
            ORDER BY s.account_id, s.snapshot_date DESC, s.created_at DESC, s.id DESC
          ), flagged AS (
            SELECT l.*, EXISTS (
                SELECT 1
                FROM import_holding_row r
                LEFT JOIN snapshot_foreign_cash c
                  ON c.snapshot_id = l.id AND c.currency = r.currency
                WHERE r.kind = 'CASH' AND r.currency <> l.currency
                  AND (r.import_batch_id = l.balance_import_batch_id
                       OR r.import_batch_id IN (
                           SELECT h.import_batch_id FROM snapshot_holding h
                           WHERE h.snapshot_id = l.id))
                GROUP BY r.import_batch_id, r.currency, c.amount
                HAVING sum(r.amount) IS DISTINCT FROM c.amount
            ) AS unrecorded_cash
            FROM latest l
          )
          SELECT s.account_id AS "accountId", s.snapshot_date AS "snapshotDate",
                 s.unrecorded_cash AS "unrecordedCash", parts.amount AS "reportedValue",
                 parts.currency AS "reportedCurrency", parts.holding AS "holding",
                 parts.cost_basis AS "reportedCostBasis",
                 parts.cost_basis_currency AS "reportedCostBasisCurrency",
                 parts.cost_in_account AS "costBasisInAccountCurrency",
                 parts.security_id AS "securityId", parts.quantity AS "quantity"
          FROM flagged s
          CROSS JOIN LATERAL (
              SELECT coalesce(s.balance, CASE WHEN EXISTS (
                         SELECT 1 FROM snapshot_foreign_cash fc WHERE fc.snapshot_id = s.id)
                       THEN 0 END) AS amount,
                     s.currency AS currency, false AS holding, NULL::numeric AS cost_basis,
                     NULL::char(3) AS cost_basis_currency, NULL::numeric AS cost_in_account,
                     NULL::uuid AS security_id, NULL::numeric AS quantity
              UNION ALL
              SELECT c.amount, c.currency, false, NULL::numeric, NULL::char(3), NULL::numeric,
                     NULL::uuid, NULL::numeric
              FROM snapshot_foreign_cash c WHERE c.snapshot_id = s.id
              UNION ALL
              SELECT h.reported_value, h.reported_currency, true, h.reported_cost_basis,
                     h.reported_cost_basis_currency, h.cost_basis_in_account_currency,
                     h.security_id, h.quantity
              FROM snapshot_holding h WHERE h.snapshot_id = s.id
          ) parts
          """,
      nativeQuery = true)
  List<ReportedHoldingValue> findLatestReportedHoldings(
      @Param("accountIds") Collection<UUID> accountIds, @Param("asOf") LocalDate asOf);

  // US-25-04: V58's partial unique index allows at most one.
  Optional<AccountSnapshot> findByAccountIdAndOpeningBalanceTrue(UUID accountId);

  // #241 review: the opening balances of several accounts at once, for a page of transactions.
  List<AccountSnapshot> findByAccountIdInAndOpeningBalanceTrue(Collection<UUID> accountIds);

  /**
   * #232 review, batched in #380: per account, the balance in effect on {@code asOf} - its newest
   * snapshot with a balance on or before that date, the opening balance included, withdrawn ones
   * (US-07-12) left out; newest by date, then by when it was recorded. A holdings-only snapshot (no
   * balance) says nothing about the value. An account without one has no row.
   */
  @Query(
      value =
          """
          SELECT DISTINCT ON (account_id) *
          FROM account_snapshot
          WHERE account_id IN (:accountIds)
            AND snapshot_date <= :asOf
            AND balance IS NOT NULL
            AND withdrawn_at IS NULL
          ORDER BY account_id, snapshot_date DESC, created_at DESC, id
          """,
      nativeQuery = true)
  List<AccountSnapshot> findLatestWithBalance(
      @Param("accountIds") Collection<UUID> accountIds, @Param("asOf") LocalDate asOf);

  /**
   * US-25-02/#482: newest observed balance per account, excluding opening, withdrawn and
   * holdings-only snapshots. Same order as findLatestObserved, id tie-break included, in one
   * PostgreSQL query.
   */
  @Query(
      value =
          """
          SELECT DISTINCT ON (account_id) *
          FROM account_snapshot
          WHERE account_id IN (:accountIds)
            AND is_opening_balance = false
            AND balance IS NOT NULL
            AND withdrawn_at IS NULL
          ORDER BY account_id, snapshot_date DESC, created_at DESC, id
          """,
      nativeQuery = true)
  List<AccountSnapshot> findLatestObservedForAccounts(
      @Param("accountIds") Collection<UUID> accountIds);

  // US-25-02: the newest observed balance, excluding the synthetic opening-balance starting point.
  // The id breaks a tie of two snapshots stated in one transaction (same created_at), so this and
  // findLatestObservedForAccounts always pick the same row (#482).
  Optional<AccountSnapshot>
      findFirstByAccountIdAndOpeningBalanceFalseAndBalanceIsNotNullAndWithdrawnAtIsNullOrderBySnapshotDateDescCreatedAtDescIdAsc(
          UUID accountId);

  // US-25-02: the observed balance immediately before a snapshot, used to bound best-effort cause
  // classification to the period that can have introduced the difference.
  Optional<AccountSnapshot>
      findFirstByAccountIdAndOpeningBalanceFalseAndBalanceIsNotNullAndWithdrawnAtIsNullAndSnapshotDateLessThanOrderBySnapshotDateDescCreatedAtDesc(
          UUID accountId, LocalDate snapshotDate);

  /**
   * US-25-02: the account's newest observed balance in effect, the opening balance and withdrawn
   * snapshots (US-07-12) left out.
   */
  default Optional<AccountSnapshot> findLatestObserved(UUID accountId) {
    return findFirstByAccountIdAndOpeningBalanceFalseAndBalanceIsNotNullAndWithdrawnAtIsNullOrderBySnapshotDateDescCreatedAtDescIdAsc(
        accountId);
  }

  /** The observed balance immediately before {@code snapshotDate} (US-25-02's cause period). */
  default Optional<AccountSnapshot> findObservedBefore(UUID accountId, LocalDate snapshotDate) {
    return findFirstByAccountIdAndOpeningBalanceFalseAndBalanceIsNotNullAndWithdrawnAtIsNullAndSnapshotDateLessThanOrderBySnapshotDateDescCreatedAtDesc(
        accountId, snapshotDate);
  }

  // US-07-12: the snapshot an import batch's commit recorded, withdrawn or not.
  Optional<AccountSnapshot> findByImportBatchId(UUID importBatchId);

  /**
   * US-07-31: the snapshot in effect a batch contributed to - it recorded it, or added its balance
   * or holdings, or filled the cost basis of its holdings (US-15-08) - if any. A batch contributes
   * to at most one (one account and date).
   */
  @Query(
      "SELECT s FROM AccountSnapshot s WHERE s.withdrawnAt IS NULL AND (s.importBatchId = :batchId"
          + " OR s.balanceImportBatchId = :batchId OR EXISTS (SELECT 1 FROM SnapshotHolding h"
          + " WHERE h.snapshotId = s.id AND (h.importBatchId = :batchId"
          + " OR h.costBasisImportBatchId = :batchId))"
          + " OR EXISTS (SELECT c.id FROM SnapshotForeignCash c"
          + " WHERE c.snapshotId = s.id AND c.importBatchId = :batchId))")
  Optional<AccountSnapshot> findContributedBy(@Param("batchId") UUID batchId);

  // Replacing a snapshot deletes and re-inserts its holdings; the row lock keeps two concurrent
  // replacements from interleaving into a mix of both holding sets.
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("SELECT s FROM AccountSnapshot s WHERE s.id = :id AND s.account.id = :accountId")
  Optional<AccountSnapshot> findForUpdate(@Param("id") UUID id, @Param("accountId") UUID accountId);
}
