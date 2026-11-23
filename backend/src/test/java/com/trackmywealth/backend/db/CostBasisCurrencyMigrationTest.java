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
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * #527: V125 gives a cost basis its currency. A position whose reported value is in the account
 * currency had a gain before, whatever its security's currency, and so had one without a value
 * whose security is in that currency: their cost basis is in that currency, so today's gains stay;
 * every other cost basis stays of unknown currency (PO 2026-11-23). Migrates to just before V125,
 * seeds positions, then applies V125.
 */
@Testcontainers
class CostBasisCurrencyMigrationTest {

  @Container
  static PostgreSQLContainer<?> postgres =
      new PostgreSQLContainer<>("postgres:16")
          .withDatabaseName("trackmywealth")
          .withUsername("trackmywealth")
          .withPassword("trackmywealth");

  private static Connection connection;
  private static UUID workspace;
  private static UUID snapshot;
  private static UUID francEtf;
  private static UUID francEtfWithoutValue;
  private static UUID dollarEtf;
  private static UUID dollarEtfValuedInFrancs;
  private static UUID francEtfQuotedInDollars;
  private static UUID withoutCostBasis;

  @BeforeAll
  static void migrateSeedAndBackfill() throws Exception {
    flyway(lastVersionBefore("125")).migrate();
    // The Testcontainers role is the bootstrap superuser: it bypasses RLS for this fixture.
    connection =
        DriverManager.getConnection(
            postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    workspace = queryUuid("INSERT INTO workspace (name) VALUES ('Before V125') RETURNING id");
    UUID depot =
        queryUuid(
            "INSERT INTO account (workspace_id, portfolio_id, account_type, name, native_currency)"
                + " SELECT ?, id, 'SECURITIES', 'Depot', 'CHF' FROM portfolio"
                + " WHERE workspace_id = ? RETURNING id",
            workspace,
            workspace);
    snapshot =
        queryUuid(
            "INSERT INTO account_snapshot (workspace_id, account_id, snapshot_date, currency)"
                + " VALUES (?, ?, DATE '2030-01-31', 'CHF') RETURNING id",
            workspace,
            depot);

    francEtf = holding("CHF", "100", "CHF", "80");
    francEtfWithoutValue = holding("CHF", null, null, "80");
    dollarEtf = holding("USD", "100", "USD", "75");
    dollarEtfValuedInFrancs = holding("USD", "100", "CHF", "410");
    francEtfQuotedInDollars = holding("CHF", "100", "USD", "80");
    withoutCostBasis = holding("CHF", "100", "CHF", null);

    flyway("125").migrate();
  }

  @AfterAll
  static void close() throws Exception {
    connection.close();
  }

  @Test
  void aCostBasisOfAPositionInTheAccountCurrencyIsInTheAccountCurrency() throws Exception {
    assertThat(costBasisCurrency(francEtf)).isEqualTo("CHF");
    assertThat(costBasisCurrency(francEtfWithoutValue)).isEqualTo("CHF");
  }

  @Test
  void aForeignSecurityValuedInTheAccountCurrencyKeepsItsGain() throws Exception {
    assertThat(costBasisCurrency(dollarEtfValuedInFrancs)).isEqualTo("CHF");
  }

  @Test
  void everyOtherCostBasisStaysOfUnknownCurrency() throws Exception {
    assertThat(costBasisCurrency(dollarEtf)).isNull();
    assertThat(costBasisCurrency(francEtfQuotedInDollars)).isNull();
    assertThat(costBasisCurrency(withoutCostBasis)).isNull();
  }

  @Test
  void aCurrencyNeedsACostBasisAndAnAccountCurrencyCostNeedsACurrency() {
    assertThatThrownBy(
            () ->
                update(
                    "UPDATE snapshot_holding SET reported_cost_basis_currency = 'CHF' WHERE id = ?",
                    withoutCostBasis))
        .isInstanceOf(SQLException.class)
        .hasMessageContaining("chk_snapshot_holding_cost_basis_currency");
    assertThatThrownBy(
            () ->
                update(
                    "UPDATE snapshot_holding SET cost_basis_in_account_currency = 70 WHERE id = ?",
                    dollarEtf))
        .isInstanceOf(SQLException.class)
        .hasMessageContaining("chk_snapshot_holding_cost_basis_in_account_currency");
  }

  @Test
  void anAccountCurrencyCostIsOnlyForACostBasisInAnotherCurrency() throws Exception {
    assertThatThrownBy(
            () ->
                update(
                    "UPDATE snapshot_holding SET cost_basis_in_account_currency = 72 WHERE id = ?",
                    francEtf))
        .isInstanceOf(SQLException.class)
        .hasMessageContaining("snapshot_holding_account_cost");

    UUID foreign = holding("USD", "100", "USD", "75");
    update(
        "UPDATE snapshot_holding SET reported_cost_basis_currency = 'USD',"
            + " cost_basis_in_account_currency = 70 WHERE id = ?",
        foreign);
    assertThat(costBasisCurrency(foreign)).isEqualTo("USD");
  }

  private static UUID holding(
      String denomination, String value, String reportedCurrency, String costBasis)
      throws SQLException {
    UUID security = UUID.randomUUID();
    update(
        "INSERT INTO security (id, synthetic_key, legal_name, display_name, denomination_currency)"
            + " VALUES (?, 'MANUAL-' || ?::TEXT, 'Test ETF', 'Test ETF', ?)",
        security,
        security,
        denomination);
    return queryUuid(
        "INSERT INTO snapshot_holding (snapshot_id, security_id, quantity, reported_value,"
            + " reported_currency, reported_cost_basis)"
            + " VALUES (?, ?, 1, ?::NUMERIC, ?, ?::NUMERIC) RETURNING id",
        snapshot,
        security,
        value,
        reportedCurrency,
        costBasis);
  }

  private static String costBasisCurrency(UUID holding) throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "SELECT reported_cost_basis_currency FROM snapshot_holding WHERE id = ?")) {
      statement.setObject(1, holding);
      try (ResultSet rs = statement.executeQuery()) {
        rs.next();
        return rs.getString(1);
      }
    }
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

  private static UUID queryUuid(String sql, Object... parameters) throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      for (int i = 0; i < parameters.length; i++) {
        statement.setObject(i + 1, parameters[i]);
      }
      try (ResultSet rs = statement.executeQuery()) {
        rs.next();
        return rs.getObject(1, UUID.class);
      }
    }
  }

  private static void update(String sql, Object... parameters) throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      for (int i = 0; i < parameters.length; i++) {
        statement.setObject(i + 1, parameters[i]);
      }
      statement.executeUpdate();
    }
  }
}
