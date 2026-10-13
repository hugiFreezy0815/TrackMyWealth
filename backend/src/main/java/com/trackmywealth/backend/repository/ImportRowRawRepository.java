package com.trackmywealth.backend.repository;

import com.trackmywealth.backend.dto.ImportDuplicateHold;
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
   * The batch's {@code DUPLICATE} rows whose booking the ledger still holds when the commit runs
   * (#287), each with the row that holds it: the transaction the row points at while that is in
   * effect - neither voided nor soft-deleted - or else what replaced it since, following a
   * correction ({@code corrects_transaction_id}) or a restored void ({@code
   * restores_transaction_id}) to the row in effect now. Each row has at most one successor ({@code
   * uq_transaction_correction}; a corrected row cannot be restored, nor a voided one corrected).
   * Any other duplicate row lost its booking since the preview: a rollback cleared its pointer, or
   * the row it pointed at was removed for good.
   */
  @Query(
      value =
          "WITH RECURSIVE chain (row_id, transaction_id) AS ("
              + " SELECT r.id, r.duplicate_of_transaction_id FROM import_row_raw r"
              + " WHERE r.import_batch_id = :batchId AND r.parse_status = 'DUPLICATE'"
              + " AND r.duplicate_of_transaction_id IS NOT NULL"
              + " UNION ALL"
              + " SELECT c.row_id, n.id FROM chain c"
              + " JOIN transaction p ON p.id = c.transaction_id"
              + " JOIN transaction n"
              + " ON n.corrects_transaction_id = p.id OR n.restores_transaction_id = p.id"
              + " WHERE p.voided_at IS NOT NULL OR p.deleted_at IS NOT NULL)"
              + " SELECT c.row_id AS rowId, c.transaction_id AS transactionId FROM chain c"
              + " JOIN transaction t ON t.id = c.transaction_id"
              + " WHERE t.voided_at IS NULL AND t.deleted_at IS NULL",
      nativeQuery = true)
  List<ImportDuplicateHold> findDuplicateHolds(@Param("batchId") UUID batchId);

  /**
   * The counts of each of {@code batchIds} that has rows, one array per batch: its id, then total,
   * new (PARSED), duplicates, errors, skipped, rows with a warning, included, imported. One query
   * for a whole page of batches.
   */
  @Query(
      value =
          "SELECT r.import_batch_id, count(*),"
              + " count(*) FILTER (WHERE r.parse_status = 'PARSED'),"
              + " count(*) FILTER (WHERE r.parse_status = 'DUPLICATE'),"
              + " count(*) FILTER (WHERE r.parse_status = 'ERROR'),"
              + " count(*) FILTER (WHERE r.parse_status = 'SKIPPED'),"
              + " count(*) FILTER (WHERE cardinality(r.warning_codes) > 0),"
              + " count(*) FILTER (WHERE r.included),"
              + " count(*) FILTER (WHERE r.resulting_transaction_id IS NOT NULL)"
              + " FROM import_row_raw r WHERE r.import_batch_id IN (:batchIds)"
              + " GROUP BY r.import_batch_id",
      nativeQuery = true)
  List<Object[]> countByBatches(@Param("batchIds") Collection<UUID> batchIds);

  /**
   * US-25-02/FR-REC-003 {@code MISSING_TRANSACTION}: whether an import of the account holds a row
   * of exactly {@code amount} in the account's {@code currency}, booked in the reconciliation
   * period, that did not reach the ledger - its batch was discarded or rolled back (US-07-05, its
   * transactions deleted), it could not be imported (an {@code ERROR} row with a readable amount)
   * or it was left out of a committed batch (a duplicate not forced in, or a row excluded) - and
   * whose booking the ledger does not hold anyway: a live row of the same date, amount and currency
   * (as the duplicate rule reads the ledger) means this one is not what is missing, e.g. a
   * discarded file uploaded again and committed, or a duplicate left out because the ledger had it.
   */
  @Query(
      value =
          "SELECT EXISTS (SELECT 1 FROM import_row_raw r"
              + " JOIN import_batch b ON b.id = r.import_batch_id"
              + " WHERE b.account_id = :accountId AND r.resulting_transaction_id IS NULL"
              + " AND r.booking_date > :after AND r.booking_date <= :asOf"
              + " AND r.amount = :amount AND r.currency = :currency"
              + " AND (b.status IN ('DISCARDED', 'ROLLED_BACK') OR r.parse_status = 'ERROR'"
              + " OR (b.status = 'COMMITTED' AND NOT r.included))"
              + " AND NOT EXISTS (SELECT 1 FROM transaction t WHERE t.account_id = b.account_id"
              + " AND t.booking_date = r.booking_date AND t.amount = r.amount"
              + " AND t.currency = r.currency AND t.deleted_at IS NULL AND t.voided_at IS NULL"
              + " AND t.replaces_transaction_id IS NULL AND t.reconciliation_result_id IS NULL))",
      nativeQuery = true)
  boolean existsNotImportedRow(
      @Param("accountId") UUID accountId,
      @Param("after") LocalDate after,
      @Param("asOf") LocalDate asOf,
      @Param("amount") BigDecimal amount,
      @Param("currency") String currency);
}
