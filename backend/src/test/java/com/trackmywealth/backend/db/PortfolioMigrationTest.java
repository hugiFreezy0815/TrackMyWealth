package com.trackmywealth.backend.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
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
 * US-04-06 (#390): V111 renames {@code financial_institution} to {@code portfolio}, V112 adds the
 * column for a portfolio type the member sets. Migrates to V110, seeds containers with accounts
 * under the old names, then runs the rest. The suggested type is derived on read and never stored,
 * so there is no backfill: the rule exists only in {@code PortfolioType#suggestedFor}, covered by
 * {@code PortfolioViewControllerTest}.
 */
@Testcontainers
class PortfolioMigrationTest {

  @Container
  static PostgreSQLContainer<?> postgres =
      new PostgreSQLContainer<>("postgres:16")
          .withDatabaseName("trackmywealth")
          .withUsername("trackmywealth")
          .withPassword("trackmywealth");

  private static Connection connection;
  private static UUID workspace;
  private static UUID pillar3a;
  private static UUID cashAndDepot;

  @BeforeAll
  static void migrateSeedAndRename() throws Exception {
    flyway("110").migrate();
    // The Testcontainers role is the bootstrap superuser: it bypasses RLS for this fixture.
    connection =
        DriverManager.getConnection(
            postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    workspace = queryUuid("INSERT INTO workspace (name) VALUES ('Before V111') RETURNING id");

    pillar3a = container("VIAC 3a");
    UUID pension = account(pillar3a, "PENSION");
    update(
        "INSERT INTO account_pension (account_id, pension_scheme) VALUES (?, 'CH_PILLAR_3A')",
        pension);

    cashAndDepot = container("PostFinance");
    account(cashAndDepot, "CASH");
    account(cashAndDepot, "SECURITIES");

    flyway(null).migrate();
  }

  @AfterAll
  static void close() throws Exception {
    connection.close();
  }

  @Test
  void everyContainerIsKeptAsAPortfolioWithItsCurrencyAndAccounts() throws Exception {
    assertThat(queryString("SELECT portfolio_currency FROM portfolio WHERE id = ?", pillar3a))
        .isEqualTo("CHF");
    assertThat(
            queryString("SELECT count(*)::TEXT FROM account WHERE portfolio_id = ?", cashAndDepot))
        .isEqualTo("2");
  }

  @Test
  void existingPortfoliosHaveNoTypeSetSoTheirTypeIsSuggested() throws Exception {
    assertThat(
            queryString(
                "SELECT count(*)::TEXT FROM portfolio"
                    + " WHERE workspace_id = ? AND portfolio_type_override IS NOT NULL",
                workspace))
        .isEqualTo("0");
  }

  @Test
  void onlyASelectableTypeCanBeSetAndNeverOnPersonalAssets() throws Exception {
    // A workspace of its own, so the fixture's portfolios keep no type set for the other tests.
    UUID own = queryUuid("INSERT INTO workspace (name) VALUES ('Types') RETURNING id");
    UUID portfolio = portfolioIn(own);
    update("UPDATE portfolio SET portfolio_type_override = 'PILLAR_3A' WHERE id = ?", portfolio);
    assertThat(queryString("SELECT portfolio_type_override FROM portfolio WHERE id = ?", portfolio))
        .isEqualTo("PILLAR_3A");

    assertThatThrownBy(
            () ->
                update(
                    "UPDATE portfolio SET portfolio_type_override = 'PERSONAL_ASSETS' WHERE id = ?",
                    portfolio))
        .isInstanceOf(SQLException.class)
        .hasMessageContaining("portfolio_type_override");
    assertThatThrownBy(
            () ->
                update(
                    "UPDATE portfolio SET portfolio_type_override = 'SAVINGS' WHERE id = ?",
                    portfolio))
        .isInstanceOf(SQLException.class);
    UUID personalAssets =
        UUID.fromString(
            queryString(
                "SELECT id::TEXT FROM portfolio WHERE workspace_id = ? AND is_personal_assets_default",
                own));
    assertThatThrownBy(
            () ->
                update(
                    "UPDATE portfolio SET portfolio_type_override = 'BANK' WHERE id = ?",
                    personalAssets))
        .isInstanceOf(SQLException.class)
        .hasMessageContaining("chk_portfolio_personal_assets_type_not_overridden");
  }

  @Test
  void noConstraintIndexOrTriggerKeepsTheOldName() throws Exception {
    assertThat(
            queryNames(
                "SELECT conname FROM pg_constraint WHERE conname LIKE '%financial_institution%'"
                    + " UNION ALL SELECT indexname FROM pg_indexes"
                    + " WHERE indexname LIKE '%financial_institution%'"
                    + " UNION ALL SELECT tgname FROM pg_trigger"
                    + " WHERE tgname LIKE '%financial_institution%'"))
        .isEmpty();
    assertThat(
            queryNames(
                "SELECT indexname FROM pg_indexes WHERE indexname = 'idx_account_portfolio'"))
        .hasSize(1);
  }

  @Test
  void aNewWorkspaceStillGetsItsPersonalAssetsPortfolio() throws Exception {
    UUID another = queryUuid("INSERT INTO workspace (name) VALUES ('After V112') RETURNING id");

    assertThat(
            queryString(
                "SELECT name || ':' || portfolio_currency || ':'"
                    + " || (portfolio_type_override IS NULL)::TEXT FROM portfolio"
                    + " WHERE workspace_id = ? AND is_personal_assets_default",
                another))
        .isEqualTo("Personal Assets:CHF:true");
  }

  @Test
  void aNewPortfolioCurrencyIsStillRecordedAsInUse() throws Exception {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "INSERT INTO portfolio (workspace_id, name, portfolio_currency) VALUES (?, 'Yen', 'JPY')")) {
      statement.setObject(1, workspace);
      statement.executeUpdate();
    }

    assertThat(queryNames("SELECT currency FROM fx_rate_currency_in_use WHERE currency = 'JPY'"))
        .containsExactly("JPY");
  }

  // Under the current schema.
  private static UUID portfolioIn(UUID workspaceId) throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "INSERT INTO portfolio (workspace_id, name, portfolio_currency)"
                + " VALUES (?, 'Vorsorge', 'CHF') RETURNING id")) {
      statement.setObject(1, workspaceId);
      return single(statement);
    }
  }

  // Under the V110 schema: the old table and column names.
  private static UUID container(String name) throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "INSERT INTO financial_institution (workspace_id, name, container_currency)"
                + " VALUES (?, ?, 'CHF') RETURNING id")) {
      statement.setObject(1, workspace);
      statement.setString(2, name);
      return single(statement);
    }
  }

  private static UUID account(UUID container, String accountType) throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "INSERT INTO account (workspace_id, financial_institution_id, account_type, name,"
                + " native_currency) VALUES (?, ?, ?, ?, 'CHF') RETURNING id")) {
      statement.setObject(1, workspace);
      statement.setObject(2, container);
      statement.setString(3, accountType);
      statement.setString(4, accountType + " account");
      return single(statement);
    }
  }

  private static void update(String sql, UUID parameter) throws SQLException {
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

  private static String queryString(String sql, UUID parameter) throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setObject(1, parameter);
      try (ResultSet result = statement.executeQuery()) {
        assertThat(result.next()).isTrue();
        return result.getString(1);
      }
    }
  }

  private static List<String> queryNames(String sql) throws SQLException {
    List<String> names = new ArrayList<>();
    try (PreparedStatement statement = connection.prepareStatement(sql);
        ResultSet result = statement.executeQuery()) {
      while (result.next()) {
        names.add(result.getString(1));
      }
    }
    return names;
  }

  private static UUID single(PreparedStatement statement) throws SQLException {
    try (ResultSet result = statement.executeQuery()) {
      assertThat(result.next()).isTrue();
      return result.getObject(1, UUID.class);
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
