package com.trackmywealth.backend.repository;

import com.trackmywealth.backend.entity.SnapshotForeignCash;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface SnapshotForeignCashRepository extends JpaRepository<SnapshotForeignCash, UUID> {

  List<SnapshotForeignCash> findBySnapshotIdInOrderByCurrency(Collection<UUID> snapshotIds);

  List<SnapshotForeignCash> findBySnapshotIdOrderByCurrency(UUID snapshotId);

  @Modifying(flushAutomatically = true)
  @Query("DELETE FROM SnapshotForeignCash c WHERE c.snapshotId = :snapshotId")
  void deleteBySnapshotId(@Param("snapshotId") UUID snapshotId);

  @Modifying(flushAutomatically = true)
  @Query(
      "DELETE FROM SnapshotForeignCash c WHERE c.snapshotId = :snapshotId"
          + " AND c.importBatchId = :batchId")
  void deleteBySnapshotIdAndImportBatchId(
      @Param("snapshotId") UUID snapshotId, @Param("batchId") UUID batchId);
}
