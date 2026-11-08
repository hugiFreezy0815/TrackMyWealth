package com.trackmywealth.backend.service;

import com.trackmywealth.backend.dto.PageResponse;
import com.trackmywealth.backend.dto.PagingValues;
import com.trackmywealth.backend.dto.TransactionHistoryEntryResponse;
import com.trackmywealth.backend.dto.TransactionImportBatchResponse;
import com.trackmywealth.backend.security.AuthenticatedUserPrincipal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * US-07-38: one paged, RLS-scoped timeline for the complete correction/restore chain.
 *
 * <p>Only the requested transaction is authorized via the same account-scoped read as #479. All
 * historical rows are constrained to that account and workspace; actor names are joined through
 * that workspace's membership, never by a global user lookup. Pagination happens in PostgreSQL
 * after combining all event sources, so the count includes every event.
 */
@Service
public class TransactionHistoryService {

  private static final String EVENTS =
      """
      WITH RECURSIVE chain AS (
          SELECT id, account_id, workspace_id, created_at, created_by, source,
                 import_batch_id, voided_at, voided_by, void_reason, deleted_at, deleted_by,
                 corrects_transaction_id, restores_transaction_id
            FROM transaction WHERE id = ? AND account_id = ? AND workspace_id = ?
          UNION ALL
          SELECT old.id, old.account_id, old.workspace_id, old.created_at, old.created_by,
                 old.source, old.import_batch_id, old.voided_at, old.voided_by,
                 old.void_reason, old.deleted_at, old.deleted_by,
                 old.corrects_transaction_id, old.restores_transaction_id
            FROM transaction old JOIN chain newer
              ON old.id = COALESCE(newer.corrects_transaction_id, newer.restores_transaction_id)
           WHERE old.account_id = newer.account_id AND old.workspace_id = newer.workspace_id
      ), category_decisions AS (
          SELECT l.id, l.transaction_id, l.assigned_at, l.category_id, l.assigned_by_user_id,
                 l.is_reset, l.is_user_override,
                 LAG(l.category_id) OVER (
                     PARTITION BY l.transaction_id ORDER BY l.assigned_at, l.id
                 ) AS previous_category
            FROM transaction_categorization_log l
            JOIN chain c ON c.id = l.transaction_id
      ), events AS (
          SELECT c.created_at AS occurred_at,
                 CASE WHEN c.import_batch_id IS NULL THEN 'CREATED' ELSE 'IMPORTED' END AS kind,
                 c.created_by AS actor_id,
                 NULL::uuid AS from_category_id, NULL::uuid AS to_category_id,
                 NULL::uuid AS related_transaction_id, c.import_batch_id AS import_batch_id,
                 NULL::text AS reason, c.id AS event_id
            FROM chain c
          UNION ALL
          SELECT l.assigned_at,
                 CASE WHEN l.is_reset THEN 'CATEGORY_RESET'
                      WHEN l.is_user_override AND l.previous_category IS NOT NULL
                           THEN 'CATEGORY_CHANGED'
                      ELSE 'CATEGORY_ASSIGNED' END,
                 l.assigned_by_user_id, l.previous_category, l.category_id,
                 NULL::uuid, NULL::uuid, NULL::text, l.id
            FROM category_decisions l
          UNION ALL
          SELECT c.voided_at, 'VOIDED', c.voided_by, NULL::uuid, NULL::uuid,
                 c.id, NULL::uuid, c.void_reason, c.id
            FROM chain c WHERE c.voided_at IS NOT NULL
          UNION ALL
          SELECT c.deleted_at, 'DELETED', c.deleted_by, NULL::uuid, NULL::uuid,
                 c.id, NULL::uuid, NULL::text, c.id
            FROM chain c WHERE c.deleted_at IS NOT NULL
          UNION ALL
          SELECT c.created_at, 'CORRECTED', c.created_by, NULL::uuid, NULL::uuid,
                 c.corrects_transaction_id, NULL::uuid, NULL::text, c.id
            FROM chain c WHERE c.corrects_transaction_id IS NOT NULL
          UNION ALL
          SELECT c.created_at, 'RESTORED', c.created_by, NULL::uuid, NULL::uuid,
                 c.restores_transaction_id, NULL::uuid, NULL::text, c.id
            FROM chain c WHERE c.restores_transaction_id IS NOT NULL
      )
      """;

  private static final String FROM =
      """
       FROM events e
       LEFT JOIN app_user u ON u.id = e.actor_id
       LEFT JOIN workspace_member m ON m.id = u.workspace_member_id AND m.workspace_id = ?
       LEFT JOIN import_batch b ON b.id = e.import_batch_id AND b.workspace_id = ?
       LEFT JOIN import_template t ON t.id = b.template_id
      """;

  private final JdbcTemplate jdbcTemplate;
  private final TransactionService transactionService;

  public TransactionHistoryService(JdbcTemplate jdbcTemplate, TransactionService transactionService) {
    this.jdbcTemplate = jdbcTemplate;
    this.transactionService = transactionService;
  }

  @Transactional(readOnly = true)
  public PageResponse<TransactionHistoryEntryResponse> history(
      UUID accountId,
      UUID transactionId,
      Pageable requested,
      AuthenticatedUserPrincipal actor) {
    // The detail read enforces account membership and hides soft-deleted rows as NOT_FOUND.
    var transaction = transactionService.getTransaction(accountId, transactionId, actor);
    Pageable pageable = PagingValues.requireBounded(requested, "occurredAt");

    UUID workspaceId = actor.workspaceId();
    Object[] params = {transaction.id(), accountId, workspaceId, workspaceId, workspaceId};
    Long count =
        jdbcTemplate.queryForObject(
            EVENTS + "SELECT COUNT(*)" + FROM, Long.class, params);
    long total = count == null ? 0 : count;
    if (total == 0) {
      return PageResponse.of(new PageImpl<>(List.of(), pageable, 0));
    }
    String direction =
        pageable.getSort().getOrderFor("occurredAt").isAscending() ? "ASC" : "DESC";
    String sql =
        EVENTS
            + """
            SELECT e.occurred_at, e.kind, m.display_name, e.from_category_id,
                   e.to_category_id, e.related_transaction_id, e.import_batch_id,
                   b.uploaded_at, t.file_format, b.source_kind, e.reason
            """
            + FROM
            + " ORDER BY e.occurred_at "
            + direction
            + ", e.kind "
            + direction
            + ", e.event_id "
            + direction
            + " LIMIT ? OFFSET ?";
    List<Object> bind = new ArrayList<>(List.of(params));
    bind.add(pageable.getPageSize());
    bind.add(pageable.getOffset());
    List<TransactionHistoryEntryResponse> rows =
        jdbcTemplate.query(sql, (rs, index) -> toEntry(rs), bind.toArray());
    return PageResponse.of(new PageImpl<>(rows, pageable, total));
  }

  private static TransactionHistoryEntryResponse toEntry(ResultSet rs) throws SQLException {
    UUID batchId = rs.getObject("import_batch_id", UUID.class);
    TransactionImportBatchResponse batch =
        batchId == null
            ? null
            : new TransactionImportBatchResponse(
                batchId,
                fileFormat(rs.getString("file_format"), rs.getString("source_kind")),
                rs.getObject("uploaded_at", OffsetDateTime.class));
    return new TransactionHistoryEntryResponse(
        rs.getObject("occurred_at", OffsetDateTime.class),
        rs.getString("kind"),
        rs.getString("display_name"),
        rs.getObject("from_category_id", UUID.class),
        rs.getObject("to_category_id", UUID.class),
        rs.getObject("related_transaction_id", UUID.class),
        batch,
        rs.getString("reason"));
  }

  static String fileFormat(String templateFormat, String sourceKind) {
    if ("PDF_TEXT".equals(templateFormat) || "PDF_OCR".equals(templateFormat)) {
      return "PDF";
    }
    if ("SPREADSHEET".equals(templateFormat) || "CSV".equals(templateFormat)) {
      return templateFormat;
    }
    return "DOCUMENT".equals(sourceKind) ? "CAMT" : "CSV";
  }
}
