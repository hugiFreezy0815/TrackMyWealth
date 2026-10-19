package com.trackmywealth.backend.repository;

import com.trackmywealth.backend.entity.ImportHoldingRow;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface ImportHoldingRowRepository extends JpaRepository<ImportHoldingRow, UUID> {

  List<ImportHoldingRow> findByImportBatchIdOrderByRowNumber(UUID importBatchId);

  // In bulk, flushed first: a re-parse inserts the batch's new lines in the same transaction, and
  // (import_batch_id, row_number) is unique.
  @Modifying(flushAutomatically = true)
  @Query("DELETE FROM ImportHoldingRow r WHERE r.importBatchId = :batchId")
  void deleteByImportBatchId(@Param("batchId") UUID batchId);
}
