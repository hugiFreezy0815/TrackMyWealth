package com.trackmywealth.backend.db;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
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
 * US-07-35 (#382): V113 gives the ledger a {@code counterparty_name} column and fills it for every
 * row imported before, from the {@code counterpartyName} the import kept in {@code
 * raw_source_data}. Migrates to just before V113, seeds rows with and without that key, then runs
 * the rest.
 */
@Testcontainers
class CounterpartyNameMigrationTest {

  @Container
  static PostgreSQLContainer<?> postgres =
      new PostgreSQLContainer<>("postgres:16")
          .withDatabaseName("trackmywealth")
          .withUsername("trackmywealth")
          .withPassword("trackmywealth");

  private static Connection connection;
  private static UUID workspace;
  private static UUID account;
  private static UUID imported;
  private static UUID importedWithBlankCounterparty;
  private static UUID importedWithoutCounterparty;
  private static UUID manual;
  private static UUID importedWithLongCounterparty;
  private static UUID importedWithAstralCounterparty;
  private static final String BANK = new String(Character.toChars(0x1F3E6));

  @BeforeAll
  static void seedRowsBeforeV113ThenMigrate() throws Exception {
    flyway(lastVersionBefore("113")).migrate();
    // The Testcontainers role is the bootstrap superuser: it bypasses RLS for this fixture.
    connection =
        DriverManager.getConnection(
            postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    workspace = queryUuid("INSERT INTO workspace (name) VALUES ('Before V113') RETURNING id");
    account = insertAccount(workspace);
    imported =
        insertTransaction(
            workspace,
            account,
            "{\"counterpartyName\": \"  Migros Zürich  \", \"cells\": {\"Betrag\": \"-12.50\"}}");
    importedWithBlankCounterparty =
        insertTransaction(workspace, account, "{\"counterpartyName\": \"   \"}");
    importedWithoutCounterparty =
        insertTransaction(workspace, account, "{\"valueDate\": \"2026-10-01\"}");
    manual = insertTransaction(workspace, account, null);
    importedWithLongCounterparty =
        insertTransaction(
            workspace, account, "{\"counterpartyName\": \"" + "y".repeat(300) + "\"}");
    importedWithAstralCounterparty =
        insertTransaction(
            workspace, account, "{\"counterpartyName\": \"z" + BANK.repeat(200) + "\"}");

    flyway(null).migrate();
  }

  @AfterAll
  static void close() throws Exception {
    connection.close();
  }

  @Test
  void anImportedRowGetsTheCounterpartyItsSourceDataNamedTrimmed() throws Exception {
    assertThat(counterpartyName(imported)).isEqualTo("Migros Zürich");
    // The source data stays as it was: it is the audit copy of what the file said.
    assertThat(text("SELECT raw_source_data ->> 'counterpartyName' FROM transaction WHERE id = ?"))
        .isEqualTo("  Migros Zürich  ");
  }

  @Test
  void aLongerCounterpartyIsCutToWhatARequestMaySendSoTheRowStaysCorrectable() throws Exception {
    assertThat(counterpartyName(importedWithLongCounterparty)).isEqualTo("y".repeat(255));
  }

  @Test
  void theCutCountsWhatTheRequestCountsAndNeverSplitsACharacter() throws Exception {
    // @Size counts UTF-16 units: an emoji counts twice. "z" plus 127 banks is 255 units.
    String stored = counterpartyName(importedWithAstralCounterparty);
    assertThat(stored).isEqualTo("z" + BANK.repeat(127));
    assertThat(stored.length()).isEqualTo(255);
  }

  @Test
  void v114StoresTheFoldedSearchTextWithASeparatorBetweenTheFields() throws Exception {
    // Lower case, no accents; U+001F keeps a search from matching across the two fields.
    assertThat(text("SELECT search_text FROM transaction WHERE id = ?"))
        .isEqualTo("card payment\u001fmigros zurich");
  }

  @Test
  void aRowWithoutAUsableCounterpartyStaysWithoutOne() throws Exception {
    assertThat(counterpartyName(importedWithBlankCounterparty)).isNull();
    assertThat(counterpartyName(importedWithoutCounterparty)).isNull();
    assertThat(counterpartyName(manual)).isNull();
  }

  @Test
  void onlyTheBackfilledRowGetsANewVersionSoAnOldEtagNoLongerMatches() throws Exception {
    // insertTransaction() stores every row at version 0.
    assertThat(version(imported)).isEqualTo(1);
    assertThat(version(importedWithBlankCounterparty)).isZero();
    assertThat(version(importedWithoutCounterparty)).isZero();
    assertThat(version(manual)).isZero();
  }

  @Test
  void theCounterpartyIsDescriptiveTextThatTheAppendOnlyRuleLetsAnEditChange() throws Exception {
    // A row of its own, so the other tests' rows stay as the migration left them.
    UUID row = insertTransaction(workspace, account, null);
    try (PreparedStatement statement =
        connection.prepareStatement(
            "UPDATE transaction SET counterparty_name = 'Coop' WHERE id = ?")) {
      statement.setObject(1, row);
      assertThat(statement.executeUpdate()).isEqualTo(1);
    }
    assertThat(counterpartyName(row)).isEqualTo("Coop");
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

  // V19's trigger gives every workspace its Personal Assets portfolio (V111 renamed the table).
  private static UUID insertAccount(UUID workspace) throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "INSERT INTO account (workspace_id, portfolio_id, account_type, name,"
                + " native_currency) SELECT ?, id, 'CASH', 'Current account', 'CHF'"
                + " FROM portfolio WHERE workspace_id = ? RETURNING id")) {
      statement.setObject(1, workspace);
      statement.setObject(2, workspace);
      return single(statement);
    }
  }

  private static UUID insertTransaction(UUID workspace, UUID account, String rawSourceData)
      throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "INSERT INTO transaction (workspace_id, account_id, transaction_type, booking_date,"
                + " amount, currency, merchant_description, raw_source_data, version)"
                + " VALUES (?, ?, 'EXPENSE', CURRENT_DATE, -12.50, 'CHF', 'Card payment',"
                + " ?::JSONB, 0) RETURNING id")) {
      statement.setObject(1, workspace);
      statement.setObject(2, account);
      statement.setString(3, rawSourceData);
      return single(statement);
    }
  }

  private static String counterpartyName(UUID transaction) throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement("SELECT counterparty_name FROM transaction WHERE id = ?")) {
      statement.setObject(1, transaction);
      try (ResultSet resultSet = statement.executeQuery()) {
        resultSet.next();
        return resultSet.getString(1);
      }
    }
  }

  private static String text(String sql) throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setObject(1, imported);
      try (ResultSet resultSet = statement.executeQuery()) {
        resultSet.next();
        return resultSet.getString(1);
      }
    }
  }

  private static long version(UUID transaction) throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement("SELECT version FROM transaction WHERE id = ?")) {
      statement.setObject(1, transaction);
      try (ResultSet resultSet = statement.executeQuery()) {
        resultSet.next();
        return resultSet.getLong(1);
      }
    }
  }

  private static UUID queryUuid(String sql) throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      return single(statement);
    }
  }

  private static UUID single(PreparedStatement statement) throws SQLException {
    try (ResultSet resultSet = statement.executeQuery()) {
      return resultSet.next() ? (UUID) resultSet.getObject(1) : null;
    }
  }
}
