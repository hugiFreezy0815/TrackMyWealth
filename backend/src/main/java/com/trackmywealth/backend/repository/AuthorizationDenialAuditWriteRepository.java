package com.trackmywealth.backend.repository;

import com.trackmywealth.backend.config.AuthorizationDenialAuditProperties;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.UUID;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.boot.jdbc.autoconfigure.DataSourceProperties;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.stereotype.Repository;

/**
 * Writes {@code authorization_denial_log} rows through a small connection pool of its own (#205).
 *
 * <p>A denial is recorded while the request still holds its connection from the main pool. Taking
 * the audit connection from that same pool ({@code REQUIRES_NEW}) let enough concurrent denials
 * wait on each other until the pool timed out. Taking it from this separate pool cannot: an audit
 * connection is never held while waiting for a main-pool one, so there is no circular wait.
 *
 * <p>Each row is a single auto-committed {@code INSERT} on its own connection, outside any Spring
 * transaction, so it is durable before the denial's 404 is sent and survives the request's
 * rollback. If it cannot be written (pool timeout, database down), the exception propagates and the
 * request fails rather than silently losing the row.
 *
 * <p>The pool is deliberately not a {@code DataSource} bean: one would make Spring Boot back off
 * its own auto-configured {@code DataSource}, which JPA and Flyway rely on.
 */
@Repository
public class AuthorizationDenialAuditWriteRepository implements DisposableBean {

  private static final String INSERT_SQL =
      "INSERT INTO authorization_denial_log"
          + " (principal_user_id, requested_entity_type, requested_entity_id, reason)"
          + " VALUES (?, ?, ?, ?)";

  private final HikariDataSource auditPool;

  public AuthorizationDenialAuditWriteRepository(
      DataSourceProperties dataSourceProperties, AuthorizationDenialAuditProperties properties) {
    HikariConfig config = new HikariConfig();
    config.setPoolName("denial-audit");
    config.setJdbcUrl(dataSourceProperties.determineUrl());
    config.setUsername(dataSourceProperties.determineUsername());
    config.setPassword(dataSourceProperties.determinePassword());
    config.setMaximumPoolSize(properties.auditPoolSize());
    config.setMinimumIdle(0);
    config.setConnectionTimeout(properties.auditConnectionTimeout().toMillis());
    config.setAutoCommit(true);
    // Open connections on first use, not at startup: most application contexts never deny.
    config.setInitializationFailTimeout(-1);
    this.auditPool = new HikariDataSource(config);
  }

  public void insert(
      UUID principalUserId, String requestedEntityType, UUID requestedEntityId, String reason) {
    try (Connection connection = auditPool.getConnection();
        PreparedStatement statement = connection.prepareStatement(INSERT_SQL)) {
      statement.setObject(1, principalUserId);
      statement.setString(2, requestedEntityType);
      statement.setObject(3, requestedEntityId);
      statement.setString(4, reason);
      statement.executeUpdate();
    } catch (SQLException ex) {
      throw new DataAccessResourceFailureException(
          "Could not write the authorization-denial audit row", ex);
    }
  }

  @Override
  public void destroy() {
    auditPool.close();
  }
}
