package com.trackmywealth.backend.repository;

import com.trackmywealth.backend.dto.TransactionHistoryEvent;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * The database side of a transaction's change history (US-07-38): one timeline of every category
 * decision and lifecycle step of a transaction and the rows it corrects or restores. JDBC, because
 * the timeline is a union over the transaction and its categorization log that no single entity
 * maps, and it is counted and paged in PostgreSQL after the union, so the count includes every
 * event. Runs in the caller's transaction, so the workspace's row-level security applies; every row
 * of the chain is further bound to the requested account and workspace, and actor names come only
 * from that workspace's members.
 */
@Repository
public class TransactionHistoryRepository {

  // Parameters: transaction id, account id, workspace id, the categorized types (comma-separated).
  //
  // chain: the requested row, then each row it corrects or restores, on the same account.
  // category_decisions: every log row of the chain, with the category before it on that row. A row
  // with no earlier log row held UNCATEGORIZED when it is of a categorized type (nothing logs
  // UNCATEGORIZED), else no category.
  // events: ordinal orders events of one instant chronologically - a row's end (VOIDED, DELETED)
  // before its replacement's start (IMPORTED, CREATED, CORRECTED, RESTORED), before an automatic
  // category decision, before a member's (an override carried to a copy, or a reset).
  private static final String EVENTS =
      """
      WITH RECURSIVE chain AS (
          SELECT id, account_id, workspace_id, transaction_type, created_at, created_by,
                 import_batch_id, voided_at, voided_by, void_reason, deleted_at, deleted_by,
                 corrects_transaction_id, restores_transaction_id
            FROM transaction WHERE id = ? AND account_id = ? AND workspace_id = ?
          UNION ALL
          SELECT old.id, old.account_id, old.workspace_id, old.transaction_type, old.created_at,
                 old.created_by, old.import_batch_id, old.voided_at, old.voided_by,
                 old.void_reason, old.deleted_at, old.deleted_by,
                 old.corrects_transaction_id, old.restores_transaction_id
            FROM transaction old JOIN chain newer
              ON old.id = COALESCE(newer.corrects_transaction_id, newer.restores_transaction_id)
           WHERE old.account_id = newer.account_id AND old.workspace_id = newer.workspace_id
      ), category_decisions AS (
          SELECT l.id, l.assigned_at, l.category_id, l.assigned_by_user_id,
                 l.is_reset, l.is_user_override, c.transaction_type,
                 LAG(l.id) OVER decisions IS NOT NULL AS has_previous,
                 LAG(l.category_id) OVER decisions AS previous_category
            FROM transaction_categorization_log l
            JOIN chain c ON c.id = l.transaction_id
          WINDOW decisions AS (
              PARTITION BY l.transaction_id
              ORDER BY l.assigned_at, (l.is_user_override OR l.is_reset), l.id
          )
      ), events AS (
          SELECT CASE WHEN c.import_batch_id IS NOT NULL
                      THEN COALESCE(ib.uploaded_at, c.created_at)
                      ELSE c.created_at END AS occurred_at,
                 CASE WHEN c.import_batch_id IS NULL THEN 'CREATED' ELSE 'IMPORTED' END AS kind,
                 CASE WHEN c.import_batch_id IS NULL THEN c.created_by
                      ELSE NULL::uuid END AS actor_id,
                 NULL::uuid AS from_category_id, NULL::uuid AS to_category_id,
                 NULL::uuid AS related_transaction_id, c.import_batch_id AS import_batch_id,
                 NULL::text AS reason, 1 AS ordinal, c.id AS event_id
            FROM chain c
            LEFT JOIN import_batch ib ON ib.id = c.import_batch_id
           WHERE c.corrects_transaction_id IS NULL AND c.restores_transaction_id IS NULL
          UNION ALL
          SELECT l.assigned_at,
                 CASE WHEN l.is_reset THEN 'CATEGORY_RESET'
                      WHEN l.is_user_override THEN 'CATEGORY_CHANGED'
                      ELSE 'CATEGORY_ASSIGNED' END,
                 l.assigned_by_user_id,
                 CASE WHEN l.has_previous THEN l.previous_category
                      WHEN l.transaction_type = ANY (string_to_array(?, ','))
                      THEN (SELECT u.id FROM category u
                             WHERE u.workspace_id IS NULL AND u.code = 'UNCATEGORIZED')
                      END,
                 l.category_id, NULL::uuid, NULL::uuid, NULL::text,
                 CASE WHEN l.is_user_override OR l.is_reset THEN 3 ELSE 2 END, l.id
            FROM category_decisions l
          UNION ALL
          SELECT c.voided_at, 'VOIDED', c.voided_by, NULL::uuid, NULL::uuid,
                 (SELECT r.id FROM transaction r
                   WHERE r.replaces_transaction_id = c.id AND r.workspace_id = c.workspace_id
                   ORDER BY r.created_at, r.id LIMIT 1),
                 NULL::uuid, c.void_reason, 0, c.id
            FROM chain c WHERE c.voided_at IS NOT NULL
          UNION ALL
          SELECT c.deleted_at, 'DELETED', c.deleted_by, NULL::uuid, NULL::uuid,
                 NULL::uuid, NULL::uuid, NULL::text, 0, c.id
            FROM chain c WHERE c.deleted_at IS NOT NULL
          UNION ALL
          SELECT c.created_at, 'CORRECTED', c.created_by, NULL::uuid, NULL::uuid,
                 c.corrects_transaction_id, NULL::uuid, NULL::text, 1, c.id
            FROM chain c WHERE c.corrects_transaction_id IS NOT NULL
          UNION ALL
          SELECT c.created_at, 'RESTORED', c.created_by, NULL::uuid, NULL::uuid,
                 c.restores_transaction_id, NULL::uuid, NULL::text, 1, c.id
            FROM chain c WHERE c.restores_transaction_id IS NOT NULL
      )
      """;

  // Parameters: workspace id (members), workspace id (import batches), limit, offset.
  private static final String PAGE =
      """
      SELECT e.occurred_at, e.kind, m.display_name, e.from_category_id, e.to_category_id,
             e.related_transaction_id, e.import_batch_id, b.uploaded_at, t.file_format,
             b.source_kind, e.reason
        FROM events e
        LEFT JOIN app_user u ON u.id = e.actor_id
        LEFT JOIN workspace_member m ON m.id = u.workspace_member_id AND m.workspace_id = ?
        LEFT JOIN import_batch b ON b.id = e.import_batch_id AND b.workspace_id = ?
        LEFT JOIN import_template t ON t.id = b.template_id
       ORDER BY e.occurred_at {direction}, e.ordinal {direction}, e.event_id {direction}
       LIMIT ? OFFSET ?
      """;

  private static final String DIRECTION = "{direction}";

  private final JdbcTemplate jdbcTemplate;

  public TransactionHistoryRepository(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  /** How many events the transaction's history has in all. */
  public long countEvents(
      UUID transactionId, UUID accountId, UUID workspaceId, Collection<String> categorizedTypes) {
    Long count =
        jdbcTemplate.queryForObject(
            EVENTS + "SELECT COUNT(*) FROM events",
            Long.class,
            transactionId,
            accountId,
            workspaceId,
            String.join(",", categorizedTypes));
    return count == null ? 0 : count;
  }

  /** One page of the transaction's events, by time - then chronologically within one instant. */
  public List<TransactionHistoryEvent> findEvents(
      UUID transactionId,
      UUID accountId,
      UUID workspaceId,
      Collection<String> categorizedTypes,
      boolean ascending,
      int limit,
      long offset) {
    // Only ever one of two constants: never text from the request.
    String direction = ascending ? "ASC" : "DESC";
    return jdbcTemplate.query(
        EVENTS + PAGE.replace(DIRECTION, direction),
        (rs, index) -> toEvent(rs),
        transactionId,
        accountId,
        workspaceId,
        String.join(",", categorizedTypes),
        workspaceId,
        workspaceId,
        limit,
        offset);
  }

  private static TransactionHistoryEvent toEvent(ResultSet rs) throws SQLException {
    return new TransactionHistoryEvent(
        rs.getObject("occurred_at", OffsetDateTime.class),
        rs.getString("kind"),
        rs.getString("display_name"),
        rs.getObject("from_category_id", UUID.class),
        rs.getObject("to_category_id", UUID.class),
        rs.getObject("related_transaction_id", UUID.class),
        rs.getObject("import_batch_id", UUID.class),
        rs.getObject("uploaded_at", OffsetDateTime.class),
        rs.getString("file_format"),
        rs.getString("source_kind"),
        rs.getString("reason"));
  }
}
