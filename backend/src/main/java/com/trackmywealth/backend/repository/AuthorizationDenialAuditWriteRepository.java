package com.trackmywealth.backend.repository;

import com.trackmywealth.backend.config.AuthorizationDenialAuditPool;
import com.trackmywealth.backend.config.AuthorizationDenialAuditProperties;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Types;
import java.util.UUID;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.stereotype.Repository;

/**
 * Writes {@code authorization_denial_log} rows through {@link AuthorizationDenialAuditPool}, never
 * the request's own pool (#205).
 *
 * <p>Each row is a single auto-committed {@code INSERT} on its own connection, outside any Spring
 * transaction, so it is durable before the denial's 404 is sent and survives the request's
 * rollback. The statement is bounded by {@code audit-statement-timeout}: the request still holds
 * its main-pool connection while it waits here, so a hung database must not keep it forever. If the
 * row cannot be written (pool timeout, statement timeout, database down), the exception propagates
 * and the request fails rather than silently losing the row.
 */
@Repository
public class AuthorizationDenialAuditWriteRepository {

  private static final String INSERT_SQL =
      "INSERT INTO authorization_denial_log"
          + " (principal_user_id, requested_entity_type, requested_entity_id, reason,"
          + " suppressed_count)"
          + " VALUES (?, ?, ?, ?, ?)";

  private final AuthorizationDenialAuditPool pool;
  private final int statementTimeoutSeconds;

  public AuthorizationDenialAuditWriteRepository(
      AuthorizationDenialAuditPool pool, AuthorizationDenialAuditProperties properties) {
    this.pool = pool;
    this.statementTimeoutSeconds = properties.auditStatementTimeoutSeconds();
  }

  /**
   * Writes one row. {@code suppressedCount} is {@code null} except on the summary row that closes a
   * throttle window, where it says how many denials in it were answered without a row of their own.
   */
  public void insert(
      UUID principalUserId,
      String requestedEntityType,
      UUID requestedEntityId,
      String reason,
      Integer suppressedCount) {
    try (Connection connection = pool.getConnection();
        PreparedStatement statement = connection.prepareStatement(INSERT_SQL)) {
      statement.setQueryTimeout(statementTimeoutSeconds);
      statement.setObject(1, principalUserId);
      statement.setString(2, requestedEntityType);
      statement.setObject(3, requestedEntityId);
      statement.setString(4, reason);
      if (suppressedCount == null) {
        statement.setNull(5, Types.INTEGER);
      } else {
        statement.setInt(5, suppressedCount);
      }
      statement.executeUpdate();
    } catch (SQLException ex) {
      throw new DataAccessResourceFailureException(
          "Could not write the authorization-denial audit row", ex);
    }
  }
}
