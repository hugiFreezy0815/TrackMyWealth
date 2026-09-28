package com.trackmywealth.backend.repository;

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
}
