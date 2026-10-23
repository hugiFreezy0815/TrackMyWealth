package com.trackmywealth.backend.repository;

import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Workspace-scoped audit writes to {@code financial_audit_log} (V17). A member's change is a
 * workspace event: RLS restricts its rows to the acting workspace, and tenant erasure (FR-LIF-020)
 * removes them with it. Administrator actions belong in {@code admin_audit_log} instead.
 */
@Repository
public class FinancialAuditLogRepository {

  private static final String INSERT_WORKSPACE_NAME_CHANGE =
      "INSERT INTO financial_audit_log (workspace_id, entity_type, entity_id, action,"
          + " actor_user_id, before_data, after_data)"
          + " VALUES (?, 'WORKSPACE', ?, 'UPDATE', ?, jsonb_build_object('name', ?::text),"
          + " jsonb_build_object('name', ?::text))";

  private final JdbcTemplate jdbcTemplate;

  public FinancialAuditLogRepository(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  public void recordWorkspaceNameChange(
      UUID workspaceId, UUID actorUserId, String beforeName, String afterName) {
    jdbcTemplate.update(
        INSERT_WORKSPACE_NAME_CHANGE, workspaceId, workspaceId, actorUserId, beforeName, afterName);
  }
}
