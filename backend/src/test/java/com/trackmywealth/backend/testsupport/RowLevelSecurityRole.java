package com.trackmywealth.backend.testsupport;

import jakarta.persistence.EntityManager;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.function.Supplier;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * A PostgreSQL role that row-level security binds ({@code NOSUPERUSER NOBYPASSRLS}). The
 * Testcontainers role every test connects as is a superuser, which bypasses every policy, so a test
 * of a policy (or of a service that depends on one) has to run its statements as such a role, or it
 * passes even with the policy missing. Granted what V20's sketched runtime role gets: DML on every
 * table and usage on every sequence.
 */
public final class RowLevelSecurityRole {

  private RowLevelSecurityRole() {}

  /** A role a test switches to inside its own transaction ({@link #call}). Idempotent. */
  public static void create(Connection admin, String role) throws SQLException {
    create(admin, role, "NOLOGIN");
  }

  /** A role a test connects as directly. Idempotent. */
  public static void createWithLogin(Connection admin, String role, String password)
      throws SQLException {
    create(admin, role, "LOGIN PASSWORD '" + password + "'");
  }

  /**
   * Runs {@code call} in one transaction that switches to {@code role} first. The transaction
   * listener has set the workspace context by then, from the security context the caller set, so
   * every statement of the services {@code call} uses (they join this transaction) is bound by RLS.
   */
  public static <T> T call(
      PlatformTransactionManager transactionManager,
      EntityManager entityManager,
      String role,
      Supplier<T> call) {
    return new TransactionTemplate(transactionManager)
        .execute(
            status -> {
              entityManager.createNativeQuery("SET LOCAL ROLE " + role).executeUpdate();
              return call.get();
            });
  }

  private static void create(Connection admin, String role, String login) throws SQLException {
    try (Statement statement = admin.createStatement()) {
      statement.execute(
          "DO $$ BEGIN IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = '"
              + role
              + "') THEN CREATE ROLE "
              + role
              + " "
              + login
              + " NOSUPERUSER NOBYPASSRLS; END IF; END $$");
      statement.execute(
          "GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO " + role);
      statement.execute("GRANT USAGE ON ALL SEQUENCES IN SCHEMA public TO " + role);
    }
  }
}
