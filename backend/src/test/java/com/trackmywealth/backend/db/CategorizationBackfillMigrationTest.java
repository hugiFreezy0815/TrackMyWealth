package com.trackmywealth.backend.db;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.Arrays;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * US-08-01: V37 categorizes the cash and card rows recorded before categorization existed - a
 * mapped MCC where the workspace may still assign its category, else UNCATEGORIZED - and leaves
 * every other row alone. Migrates to just before V37, seeds such rows, then runs the rest.
 */
@Testcontainers
class CategorizationBackfillMigrationTest {

  @Container
  static PostgreSQLContainer<?> postgres =
      new PostgreSQLContainer<>("postgres:16")
          .withDatabaseName("trackmywealth")
          .withUsername("trackmywealth")
          .withPassword("trackmywealth");

  private static Connection connection;
  private static UUID mappedMcc;
  private static UUID mappedNumericMcc;
  private static UUID noSourceCode;
  private static UUID settlement;
  private static UUID transfer;
  private static UUID alreadyCategorized;
  private static UUID deactivatedTarget;

  @BeforeAll
  static void migrateSeedAndBackfill() throws Exception {
    // Whatever the last migration before V37 is, so this keeps working as earlier ones are added.
    flyway(lastVersionBefore("37")).migrate();
    // The Testcontainers role is the bootstrap superuser: it bypasses RLS for this fixture.
    connection =
        DriverManager.getConnection(
            postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());

    UUID workspace = insertWorkspace("Before categorization");
    UUID account = insertAccount(workspace);
    mappedMcc =
        insertTransaction(workspace, account, "CREDIT_CARD_PURCHASE", "{\"mcc\": \"5411\"}");
    // An importer may store the MCC as a JSON number.
    mappedNumericMcc = insertTransaction(workspace, account, "EXPENSE", "{\"mcc\": 5812}");
    noSourceCode = insertTransaction(workspace, account, "EXPENSE", null);
    settlement = insertTransaction(workspace, account, "SETTLEMENT", null);
    transfer = insertTransaction(workspace, account, "TRANSFER", null);
    alreadyCategorized = insertTransaction(workspace, account, "EXPENSE", "{\"mcc\": \"5411\"}");
    UUID other = queryUuid("SELECT id FROM category WHERE workspace_id IS NULL AND code = 'OTHER'");
    update("UPDATE transaction SET category_id = ? WHERE id = ?", other, alreadyCategorized);

    // A workspace that deactivated GROCERIES for itself (V34 override) gets UNCATEGORIZED instead.
    UUID customised = insertWorkspace("Without groceries");
    UUID groceries =
        queryUuid("SELECT id FROM category WHERE workspace_id IS NULL AND code = 'GROCERIES'");
    try (PreparedStatement statement =
        connection.prepareStatement(
            "INSERT INTO workspace_category_override (workspace_id, category_id, is_active)"
                + " VALUES (?, ?, FALSE)")) {
      statement.setObject(1, customised);
      statement.setObject(2, groceries);
      statement.executeUpdate();
    }
    deactivatedTarget =
        insertTransaction(customised, insertAccount(customised), "EXPENSE", "{\"mcc\": \"5411\"}");

    flyway(null).migrate();
  }

  @AfterAll
  static void close() throws Exception {
    connection.close();
  }

  @Test
  void aMappedMccIsAssignedAndLoggedAsSourceCode() throws Exception {
    assertThat(categoryCode(mappedMcc)).isEqualTo("GROCERIES");
    assertThat(logAssignedBy(mappedMcc)).isEqualTo("SOURCE_CODE");
    assertThat(categoryCode(mappedNumericMcc)).isEqualTo("DINING");
  }

  @Test
  void aRowWithNothingToGoOnIsUncategorizedWithoutALogRow() throws Exception {
    assertThat(categoryCode(noSourceCode)).isEqualTo("UNCATEGORIZED");
    assertThat(logAssignedBy(noSourceCode)).isNull();
  }

  @Test
  void aCategoryTheWorkspaceDeactivatedIsNotAssigned() throws Exception {
    assertThat(categoryCode(deactivatedTarget)).isEqualTo("UNCATEGORIZED");
  }

  @Test
  void settlementsTransfersAndCategorizedRowsAreLeftAlone() throws Exception {
    assertThat(categoryCode(settlement)).isNull();
    assertThat(categoryCode(transfer)).isNull();
    assertThat(categoryCode(alreadyCategorized)).isEqualTo("OTHER");
    assertThat(logAssignedBy(alreadyCategorized)).isNull();
  }

  @Test
  void theNewDefaultsSitWhereTheyBelong() throws Exception {
    assertThat(parentCode("DINING")).isEqualTo("LEISURE");
    assertThat(parentCode("TRAVEL")).isEqualTo("LEISURE");
    assertThat(parentCode("UTILITIES")).isEqualTo("HOUSING");
    assertThat(parentCode("HEALTH")).isNull();
    assertThat(parentCode("SHOPPING")).isNull();
    assertThat(parentCode("TAXES")).isNull();
  }

  private static String lastVersionBefore(String version) {
    MigrationVersion limit = MigrationVersion.fromVersion(version);
    return Arrays.stream(flyway(null).info().all())
        .map(MigrationInfo::getVersion)
        .filter(candidate -> candidate.compareTo(limit) < 0)
        .max(MigrationVersion::compareTo)
        .orElseThrow()
        .getVersion();
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

  private static UUID insertWorkspace(String name) throws Exception {
    return queryUuid("INSERT INTO workspace (name) VALUES ('" + name + "') RETURNING id");
  }

  // V19's trigger gives every workspace its Personal Assets container.
  private static UUID insertAccount(UUID workspace) throws Exception {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "INSERT INTO account (workspace_id, financial_institution_id, account_type, name,"
                + " native_currency) SELECT ?, id, 'CASH', 'Checking', 'CHF'"
                + " FROM financial_institution WHERE workspace_id = ? RETURNING id")) {
      statement.setObject(1, workspace);
      statement.setObject(2, workspace);
      return single(statement);
    }
  }

  private static UUID insertTransaction(UUID workspace, UUID account, String type, String raw)
      throws Exception {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "INSERT INTO transaction (workspace_id, account_id, transaction_type, booking_date,"
                + " amount, currency, raw_source_data)"
                + " VALUES (?, ?, ?, CURRENT_DATE, -10, 'CHF', CAST(? AS jsonb)) RETURNING id")) {
      statement.setObject(1, workspace);
      statement.setObject(2, account);
      statement.setString(3, type);
      statement.setString(4, raw);
      return single(statement);
    }
  }

  private static void update(String sql, UUID first, UUID second) throws Exception {
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setObject(1, first);
      statement.setObject(2, second);
      statement.executeUpdate();
    }
  }

  private static String categoryCode(UUID transaction) throws Exception {
    return queryString(
        "SELECT c.code FROM transaction t LEFT JOIN category c ON c.id = t.category_id"
            + " WHERE t.id = ?",
        transaction);
  }

  private static String logAssignedBy(UUID transaction) throws Exception {
    return queryString(
        "SELECT (SELECT assigned_by FROM transaction_categorization_log WHERE transaction_id = ?)",
        transaction);
  }

  private static String parentCode(String code) throws Exception {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "SELECT p.code FROM category c LEFT JOIN category p ON p.id = c.parent_category_id"
                + " WHERE c.workspace_id IS NULL AND c.code = ?")) {
      statement.setString(1, code);
      try (ResultSet resultSet = statement.executeQuery()) {
        assertThat(resultSet.next()).as(code).isTrue();
        return resultSet.getString(1);
      }
    }
  }

  private static String queryString(String sql, UUID parameter) throws Exception {
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setObject(1, parameter);
      try (ResultSet resultSet = statement.executeQuery()) {
        resultSet.next();
        return resultSet.getString(1);
      }
    }
  }

  private static UUID queryUuid(String sql) throws Exception {
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      return single(statement);
    }
  }

  private static UUID single(PreparedStatement statement) throws Exception {
    try (ResultSet resultSet = statement.executeQuery()) {
      return resultSet.next() ? (UUID) resultSet.getObject(1) : null;
    }
  }
}
