package com.trackmywealth.backend.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * #241 review: V58 makes a credit card's snapshots follow its billing currency. A card snapshot
 * recorded earlier under V33's native-currency rule, on a card whose two currencies differ, cannot
 * be converted without a rate, so V58 stops and names it rather than relabel it silently. Migrates
 * to just before V58, seeds such a snapshot next to a harmless one, then runs the rest.
 */
@Testcontainers
class CardSnapshotCurrencyMigrationTest {

  @Container
  static PostgreSQLContainer<?> postgres =
      new PostgreSQLContainer<>("postgres:16")
          .withDatabaseName("trackmywealth")
          .withUsername("trackmywealth")
          .withPassword("trackmywealth");

  private static Connection connection;
  private static UUID workspace;
  private static UUID dollarCardBilledInFrancs;
  private static UUID francCard;

  @BeforeAll
  static void migrateToJustBeforeV58() throws Exception {
    flyway(lastVersionBefore("58")).migrate();
    // The Testcontainers role is the bootstrap superuser: it bypasses RLS for this fixture.
    connection =
        DriverManager.getConnection(
            postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    workspace = queryUuid("INSERT INTO workspace (name) VALUES ('Before V58') RETURNING id");
    dollarCardBilledInFrancs = insertCard("USD", "CHF");
    francCard = insertCard("CHF", "CHF");
  }

  @AfterAll
  static void close() throws Exception {
    connection.close();
  }

  @Test
  void aCardSnapshotInTheNativeCurrencyStopsTheMigrationUntilItIsResolved() throws Exception {
    // Allowed by V33: a snapshot in the card's native currency.
    UUID mislabeled = insertSnapshot(dollarCardBilledInFrancs, "USD");
    UUID harmless = insertSnapshot(francCard, "CHF");

    assertThatThrownBy(() -> flyway(null).migrate())
        .isInstanceOf(FlywayException.class)
        .hasMessageContaining("account_snapshot_card_currency")
        .hasMessageContaining(mislabeled.toString())
        .hasMessageNotContaining(harmless.toString());
    // PostgreSQL rolls the whole migration back: V58's index is not there.
    assertThat(indexExists("uq_account_snapshot_opening_balance")).isFalse();

    // The documented fix: delete it (or re-record it in the billing currency), then restart.
    execute("DELETE FROM account_snapshot WHERE id = ?", mislabeled);
    flyway(null).repair();
    flyway(null).migrate();

    assertThat(indexExists("uq_account_snapshot_opening_balance")).isTrue();
    // From now on the card's snapshots are in its billing currency.
    assertThat(insertSnapshot(dollarCardBilledInFrancs, "CHF")).isNotNull();
    assertThatThrownBy(() -> insertSnapshot(dollarCardBilledInFrancs, "USD"))
        .isInstanceOf(SQLException.class)
        .hasMessageContaining("account_snapshot_currency_mismatch");
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

  // V19's trigger gives every workspace its Personal Assets container.
  private static UUID insertCard(String nativeCurrency, String billingCurrency) throws Exception {
    UUID account;
    try (PreparedStatement statement =
        connection.prepareStatement(
            "INSERT INTO account (workspace_id, financial_institution_id, account_type, name,"
                + " native_currency) SELECT ?, id, 'CREDIT_CARD', 'Card', ?"
                + " FROM financial_institution WHERE workspace_id = ? RETURNING id")) {
      statement.setObject(1, workspace);
      statement.setString(2, nativeCurrency);
      statement.setObject(3, workspace);
      account = single(statement);
    }
    try (PreparedStatement statement =
        connection.prepareStatement(
            "INSERT INTO account_credit_card (account_id, billing_currency) VALUES (?, ?)")) {
      statement.setObject(1, account);
      statement.setString(2, billingCurrency);
      statement.executeUpdate();
    }
    return account;
  }

  private static UUID insertSnapshot(UUID account, String currency) throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "INSERT INTO account_snapshot (workspace_id, account_id, snapshot_date, balance,"
                + " currency) VALUES (?, ?, CURRENT_DATE - (SELECT count(*)::INT FROM"
                + " account_snapshot WHERE account_id = ?), 100, ?) RETURNING id")) {
      statement.setObject(1, workspace);
      statement.setObject(2, account);
      statement.setObject(3, account);
      statement.setString(4, currency);
      return single(statement);
    }
  }

  private static boolean indexExists(String name) throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement("SELECT to_regclass(?) IS NOT NULL")) {
      statement.setString(1, name);
      try (ResultSet resultSet = statement.executeQuery()) {
        resultSet.next();
        return resultSet.getBoolean(1);
      }
    }
  }

  private static void execute(String sql, UUID parameter) throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setObject(1, parameter);
      statement.executeUpdate();
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
