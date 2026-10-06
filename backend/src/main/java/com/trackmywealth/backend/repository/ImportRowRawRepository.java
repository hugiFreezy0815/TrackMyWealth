package com.trackmywealth.backend.repository;

import com.trackmywealth.backend.entity.ImportRowRaw;
import jakarta.persistence.QueryHint;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.hibernate.jpa.HibernateHints;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/** US-07-04: the rows of an import batch, read in file order. */
@Repository
public interface ImportRowRawRepository extends JpaRepository<ImportRowRaw, UUID> {

  String BATCH_ID = "batchId";

  Page<ImportRowRaw> findByImportBatchIdOrderByRowNumber(UUID importBatchId, Pageable pageable);

  Page<ImportRowRaw> findByImportBatchIdAndParseStatusOrderByRowNumber(
      UUID importBatchId, String parseStatus, Pageable pageable);

  Optional<ImportRowRaw> findByImportBatchIdAndRowNumber(UUID importBatchId, int rowNumber);

  // Read-only: the commit only reads them (and updates them in bulk), so thousands of rows add
  // nothing to each flush's dirty check.
  @QueryHints(@QueryHint(name = HibernateHints.HINT_READ_ONLY, value = "true"))
  List<ImportRowRaw> findByImportBatchIdAndParseStatusInOrderByRowNumber(
      UUID importBatchId, Collection<String> parseStatuses);

  List<ImportRowRaw> findByImportBatchIdAndParseStatusOrderByRowNumber(
      UUID importBatchId, String parseStatus);

  /**
   * The batch's counts, one array: total, new (PARSED), duplicates, errors, rows with a warning,
   * included, imported.
   */
  @Query(
      value =
          "SELECT count(*),"
              + " count(*) FILTER (WHERE r.parse_status = 'PARSED'),"
              + " count(*) FILTER (WHERE r.parse_status = 'DUPLICATE'),"
              + " count(*) FILTER (WHERE r.parse_status = 'ERROR'),"
              + " count(*) FILTER (WHERE cardinality(r.warning_codes) > 0),"
              + " count(*) FILTER (WHERE r.included),"
              + " count(*) FILTER (WHERE r.resulting_transaction_id IS NOT NULL)"
              + " FROM import_row_raw r WHERE r.import_batch_id = :batchId",
      nativeQuery = true)
  List<Object[]> countByBatch(@Param(BATCH_ID) UUID batchId);

  /**
   * US-25-02/FR-REC-003 {@code MISSING_TRANSACTION}: whether an import of the account holds a row
   * of exactly {@code amount} in the account's {@code currency}, booked in the reconciliation
   * period, that did not reach the ledger - its batch was discarded, it could not be imported (an
   * {@code ERROR} row with a readable amount) or it was left out of a committed batch (a duplicate
   * not forced in, or a row excluded).
   */
  @Query(
      value =
          "SELECT EXISTS (SELECT 1 FROM import_row_raw r"
              + " JOIN import_batch b ON b.id = r.import_batch_id"
              + " WHERE b.account_id = :accountId AND r.resulting_transaction_id IS NULL"
              + " AND r.booking_date > :after AND r.booking_date <= :asOf"
              + " AND r.amount = :amount AND r.currency = :currency"
              + " AND (b.status = 'DISCARDED' OR r.parse_status = 'ERROR'"
              + " OR (b.status = 'COMMITTED' AND NOT r.included)))",
      nativeQuery = true)
  boolean existsNotImportedRow(
      @Param("accountId") UUID accountId,
      @Param("after") LocalDate after,
      @Param("asOf") LocalDate asOf,
      @Param("amount") BigDecimal amount,
      @Param("currency") String currency);
}
