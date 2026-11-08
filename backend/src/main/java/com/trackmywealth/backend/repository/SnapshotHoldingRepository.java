package com.trackmywealth.backend.repository;

import com.trackmywealth.backend.dto.AccountPositionCount;
import com.trackmywealth.backend.entity.SnapshotHolding;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface SnapshotHoldingRepository extends JpaRepository<SnapshotHolding, UUID> {

  List<SnapshotHolding> findBySnapshotIdIn(Collection<UUID> snapshotIds);

  // A bulk DELETE rather than deleteAll(entities): the replacement rows are inserted in the same
  // transaction, and uq_snapshot_holding_security (V33) needs the old rows gone first - Hibernate
  // would otherwise flush the inserts before the entity deletes.
  @Modifying(flushAutomatically = true)
  @Query("DELETE FROM SnapshotHolding h WHERE h.snapshotId = :snapshotId")
  void deleteBySnapshotId(@Param("snapshotId") UUID snapshotId);

  List<SnapshotHolding> findBySnapshotId(UUID snapshotId);

  // US-07-31: a rollback takes out only its own batch's holdings of a completed snapshot.
  @Modifying(flushAutomatically = true)
  @Query(
      "DELETE FROM SnapshotHolding h WHERE h.snapshotId = :snapshotId AND h.importBatchId ="
          + " :batchId")
  void deleteBySnapshotIdAndImportBatchId(
      @Param("snapshotId") UUID snapshotId, @Param("batchId") UUID batchId);

  /**
   * US-04-06: per account, the number of positions in its newest snapshot that lists any - newest
   * by snapshot date, then by when it was recorded, then by id (the same order as US-15-05's
   * positions endpoint), withdrawn snapshots (US-07-12) left out - and that snapshot's date. An
   * account without such a snapshot has no row.
   *
   * <p>A snapshot without holdings is skipped: it may be a balance-only statement that says nothing
   * about positions, so it cannot count as "no positions". The count can therefore be older than
   * the account's newest snapshot, which is why its date is returned with it.
   */
  @Query(
      value =
          """
          SELECT DISTINCT ON (snapshot.account_id)
              snapshot.account_id AS account_id,
              count(holding.id) AS positions,
              snapshot.snapshot_date AS positions_as_of
          FROM account_snapshot AS snapshot
          JOIN snapshot_holding AS holding ON holding.snapshot_id = snapshot.id
          WHERE snapshot.account_id IN (:accountIds) AND snapshot.withdrawn_at IS NULL
          GROUP BY snapshot.account_id, snapshot.id, snapshot.snapshot_date, snapshot.created_at
          ORDER BY snapshot.account_id, snapshot.snapshot_date DESC, snapshot.created_at DESC,
              snapshot.id DESC
          """,
      nativeQuery = true)
  List<AccountPositionCount> countPositionsInNewestHoldingsSnapshot(
      @Param("accountIds") Collection<UUID> accountIds);
}
