package com.trackmywealth.backend.repository;

import com.trackmywealth.backend.dto.ImportRollbackValues;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * The database side of rolling back an import batch (US-07-05): which of its transactions someone
 * worked on, and the one hard delete of transactions the schema allows (V68). JDBC, because each
 * question spans tables no single entity maps, and the delete must run next to the setting that
 * permits it. Runs in the caller's transaction, so the workspace's row-level security applies; the
 * caller flushes JPA first.
 */
@Repository
public class ImportRollbackRepository {

  /** The transaction-local setting {@code trg_transaction_no_hard_delete} checks (V68). */
  static final String ROLLBACK_SETTING = "app.import_rollback_batch_id";

  private static final String BATCH_ROWS =
      "SELECT t.id FROM transaction t WHERE t.import_batch_id = ?";

  /**
   * One query per criterion, each the batch's transactions it applies to (soft-deleted and voided
   * rows included). The predicates follow the definition of "modified" on issue #231.
   */
  static final Map<String, String> CRITERIA = criterionPredicates();

  private final JdbcTemplate jdbcTemplate;

  public ImportRollbackRepository(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  private static Map<String, String> criterionPredicates() {
    Map<String, String> criteria = new LinkedHashMap<>();
    criteria.put(
        ImportRollbackValues.CORRECTED,
        " AND EXISTS (SELECT 1 FROM transaction c WHERE c.corrects_transaction_id = t.id)");
    // A correction soft-deletes the row it corrects; that removal is the correction's.
    criteria.put(
        ImportRollbackValues.REMOVED,
        " AND (t.voided_at IS NOT NULL OR t.deleted_at IS NOT NULL)"
            + " AND NOT EXISTS (SELECT 1 FROM transaction c WHERE c.corrects_transaction_id = t.id)");
    // Any override ever made counts, a reset one too: a member worked on the row.
    criteria.put(
        ImportRollbackValues.USER_CATEGORY_OVERRIDE,
        " AND EXISTS (SELECT 1 FROM transaction_categorization_log l"
            + " WHERE l.transaction_id = t.id AND l.is_user_override)");
    criteria.put(
        ImportRollbackValues.CATEGORY_SPLIT,
        " AND EXISTS (SELECT 1 FROM transaction_category_split s WHERE s.transaction_id = t.id)");
    // Only a member's decision: one the system took (decided_by NULL) is undone with the rows.
    criteria.put(
        ImportRollbackValues.MATCH_DECIDED,
        " AND EXISTS (SELECT 1 FROM settlement_match m"
            + " WHERE (m.payment_transaction_id = t.id OR m.card_transaction_id = t.id)"
            + " AND m.status IN ('CONFIRMED', 'REJECTED') AND m.decided_by IS NOT NULL)");
    criteria.put(
        ImportRollbackValues.RECONCILIATION_RESOLUTION,
        " AND EXISTS (SELECT 1 FROM reconciliation_result r"
            + " WHERE r.resolution_transaction_id = t.id)");
    criteria.put(
        ImportRollbackValues.RESTORED,
        " AND EXISTS (SELECT 1 FROM transaction c WHERE c.restores_transaction_id = t.id)");
    // Rows of the batch may point at each other; anything else pointing in keeps the row.
    criteria.put(
        ImportRollbackValues.REFERENCED,
        " AND (EXISTS (SELECT 1 FROM transaction o WHERE o.related_transaction_id = t.id"
            + " AND o.import_batch_id IS DISTINCT FROM t.import_batch_id)"
            + " OR EXISTS (SELECT 1 FROM import_row_raw r"
            + " WHERE r.duplicate_of_transaction_id = t.id)"
            + " OR EXISTS (SELECT 1 FROM tax_lot l WHERE l.acquisition_transaction_id = t.id)"
            + " OR EXISTS (SELECT 1 FROM tax_lot_disposal d"
            + " WHERE d.disposal_transaction_id = t.id))");
    return criteria;
  }

  /** The ids of the batch's transactions, whatever their state, in id order. */
  public List<UUID> findTransactionIds(UUID batchId) {
    return jdbcTemplate.queryForList(BATCH_ROWS + " ORDER BY t.id", UUID.class, batchId);
  }

  /**
   * Locks every transaction of the batch, in id order (as two removals lock cards), so nobody
   * corrects, categorizes or removes one of them while the rollback decides and acts.
   */
  public void lockTransactions(UUID batchId) {
    jdbcTemplate.queryForList(BATCH_ROWS + " ORDER BY t.id FOR UPDATE", UUID.class, batchId);
  }

  /**
   * Per criterion of {@link ImportRollbackValues#CRITERIA}, in that order, the batch's transactions
   * it applies to; a criterion that applies to none is absent.
   */
  public Map<String, List<UUID>> findModified(UUID batchId) {
    Map<String, List<UUID>> modified = new LinkedHashMap<>();
    CRITERIA.forEach(
        (criterion, predicate) -> {
          List<UUID> ids =
              jdbcTemplate.queryForList(
                  BATCH_ROWS + predicate + " ORDER BY t.id", UUID.class, batchId);
          if (!ids.isEmpty()) {
            modified.put(criterion, ids);
          }
        });
    return modified;
  }

  /** The earliest booking date among the batch's transactions; {@code null} without any. */
  public LocalDate findEarliestBookingDate(UUID batchId) {
    return jdbcTemplate.queryForObject(
        "SELECT min(booking_date) FROM transaction WHERE import_batch_id = ?",
        LocalDate.class,
        batchId);
  }

  /**
   * FR-LIF-010: deletes every transaction of an unmodified batch and what only described them - the
   * settlement and transfer matches they are a leg of (the caller has cleared the other legs'
   * flags), their categorization log - and unlinks the batch's rows from them. The rows, the batch
   * and its file stay. Only here does {@code trg_transaction_no_hard_delete} let a delete through.
   *
   * @return how many transactions were deleted
   */
  public int deleteTransactions(UUID batchId) {
    String batchRows = "(" + BATCH_ROWS + ")";
    jdbcTemplate.update(
        "DELETE FROM settlement_match WHERE payment_transaction_id IN "
            + batchRows
            + " OR card_transaction_id IN "
            + batchRows,
        batchId,
        batchId);
    jdbcTemplate.update(
        "DELETE FROM transaction_categorization_log WHERE transaction_id IN " + batchRows, batchId);
    jdbcTemplate.update(
        "UPDATE import_row_raw SET resulting_transaction_id = NULL"
            + " WHERE import_batch_id = ? AND resulting_transaction_id IS NOT NULL",
        batchId);
    // Transaction-local (TRUE): a rollback of the surrounding transaction takes the permit too.
    jdbcTemplate.queryForObject(
        "SELECT set_config(?, ?, TRUE)", String.class, ROLLBACK_SETTING, batchId.toString());
    int deleted = jdbcTemplate.update("DELETE FROM transaction WHERE import_batch_id = ?", batchId);
    // Withdrawn at once: nothing later in the same transaction may delete under it.
    jdbcTemplate.queryForObject("SELECT set_config(?, '', TRUE)", String.class, ROLLBACK_SETTING);
    return deleted;
  }
}
