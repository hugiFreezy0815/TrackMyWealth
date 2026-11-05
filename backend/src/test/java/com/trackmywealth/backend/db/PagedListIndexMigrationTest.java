package com.trackmywealth.backend.db;

import static org.assertj.core.api.Assertions.assertThat;

import com.trackmywealth.backend.testsupport.RowLevelSecurityRole;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * US-29-06 (#395): V115 changes no page. The fixture ({@link PagedListFixture}) migrates through
 * V114, records eight pages, runs V115 and reads them again: same rows, same order. Which index
 * each page is read through after V115, and that no plan sorts, is {@link PagedListPlanTest}'s job,
 * on the SQL Hibernate generates. This class needs the migration in two steps, so it runs Flyway
 * and the reads over plain JDBC.
 *
 * <p>The SQL mirrors the repositories' derived queries; whatever it differs in, it is the same
 * before and after the migration. Every read runs as a {@code NOSUPERUSER NOBYPASSRLS} role inside
 * the workspace context, as the application's runtime role does, so the policy's filter is part of
 * the pages compared.
 */
@Testcontainers
class PagedListIndexMigrationTest {

  @Container
  static PostgreSQLContainer<?> postgres =
      new PostgreSQLContainer<>("postgres:16")
          .withDatabaseName("trackmywealth")
          .withUsername("trackmywealth")
          .withPassword("trackmywealth");

  private static final String RUNTIME_ROLE = "paged_index_runtime";

  private static final String LEDGER =
      "SELECT id FROM transaction WHERE account_id = ? AND deleted_at IS NULL"
          + " ORDER BY booking_date DESC, created_at DESC, id DESC LIMIT 50 OFFSET %d";
  private static final String AMOUNT =
      "SELECT id FROM transaction WHERE account_id = ? AND deleted_at IS NULL"
          + " ORDER BY amount_in_account_currency DESC, id DESC LIMIT 50 OFFSET 0";
  private static final String UNCATEGORIZED =
      "SELECT id FROM transaction WHERE account_id = ? AND category_id = ?"
          + " AND voided_at IS NULL AND replaces_transaction_id IS NULL AND deleted_at IS NULL"
          + " ORDER BY booking_date DESC, created_at DESC, id DESC LIMIT 50 OFFSET %d";
  private static final String BATCHES =
      "SELECT id FROM import_batch WHERE account_id = ?"
          + " ORDER BY uploaded_at DESC, id DESC LIMIT 50";
  private static final String RECONCILIATIONS =
      "SELECT id FROM reconciliation_result WHERE account_id = ?"
          + " AND affected_security_id IS NULL ORDER BY created_at DESC, id DESC LIMIT 50";
  private static final String CATALOGUE =
      "SELECT id FROM institution_catalogue WHERE is_active ORDER BY name, id LIMIT 50";

  private static Connection connection;
  private static PagedListFixture.Ids fixture;
  private static List<List<UUID>> pagesBeforeMigration;

  @BeforeAll
  static void migrateAndSeed() throws SQLException {
    flyway("114").migrate();
    connection =
        DriverManager.getConnection(
            postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    // V115 adds no table, so the grants made now cover every table the reads touch.
    RowLevelSecurityRole.create(connection, RUNTIME_ROLE);
    fixture = PagedListFixture.seed(connection);
    PagedListFixture.settle(connection);
    pagesBeforeMigration = pages();
    flyway(null).migrate();
    PagedListFixture.settle(connection);
  }

  @AfterAll
  static void close() throws SQLException {
    if (connection != null) {
      connection.close();
    }
  }

  @Test
  void migrationDoesNotChangeAnyPagedListOrderOrMembership() throws SQLException {
    assertThat(pagesBeforeMigration).allSatisfy(page -> assertThat(page).hasSize(50));
    assertThat(pages()).containsExactlyElementsOf(pagesBeforeMigration);
  }

  @Test
  void oldPrefixIndexesAreReplacedWithoutDroppingTheStatusIndex() throws SQLException {
    assertThat(indexExists("idx_transaction_account_date")).isFalse();
    // Its account-led successor, for roles that bypass RLS (see V115).
    assertThat(indexExists("idx_transaction_account_date_order")).isTrue();
    assertThat(indexExists("idx_import_batch_account")).isFalse();
    assertThat(indexExists("idx_reconciliation_result_account")).isTrue();
    assertThat(indexExists("idx_transaction_workspace_ledger_order")).isTrue();
    assertThat(indexExists("idx_transaction_workspace_category_order")).isTrue();
  }

  private static List<List<UUID>> pages() throws SQLException {
    UUID account = fixture.account();
    UUID uncategorized = fixture.uncategorized();
    return List.of(
        ids(LEDGER.formatted(0), account),
        ids(LEDGER.formatted(25_000), account),
        ids(AMOUNT, account),
        ids(UNCATEGORIZED.formatted(0), account, uncategorized),
        ids(UNCATEGORIZED.formatted(450), account, uncategorized),
        ids(BATCHES, account),
        ids(RECONCILIATIONS, account),
        ids(CATALOGUE));
  }

  /**
   * Reads {@code sql}'s ids in one transaction as {@link #RUNTIME_ROLE} with the fixture's
   * workspace as the RLS context - what WorkspaceContextTransactionExecutionListener sets for a
   * request - and rolls it back, so the role and the setting end with it.
   */
  private static List<UUID> ids(String sql, UUID... parameters) throws SQLException {
    connection.setAutoCommit(false);
    try {
      try (Statement statement = connection.createStatement()) {
        statement.execute("SET LOCAL ROLE " + RUNTIME_ROLE);
      }
      try (PreparedStatement statement =
          connection.prepareStatement("SELECT set_config('app.current_workspace_id', ?, true)")) {
        statement.setString(1, fixture.workspace().toString());
        statement.execute();
      }
      List<UUID> result = new ArrayList<>();
      try (PreparedStatement statement = connection.prepareStatement(sql)) {
        PagedListFixture.bind(statement, (Object[]) parameters);
        try (ResultSet rows = statement.executeQuery()) {
          while (rows.next()) {
            result.add(rows.getObject(1, UUID.class));
          }
        }
      }
      return result;
    } finally {
      connection.rollback();
      connection.setAutoCommit(true);
    }
  }

  private static boolean indexExists(String name) throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement("SELECT to_regclass(?) IS NOT NULL")) {
      statement.setString(1, name);
      try (ResultSet rows = statement.executeQuery()) {
        rows.next();
        return rows.getBoolean(1);
      }
    }
  }

  private static Flyway flyway(String target) {
    var configuration =
        Flyway.configure()
            .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    if (target != null) {
      configuration.target(target);
    }
    return configuration.load();
  }
}
