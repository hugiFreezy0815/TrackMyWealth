package com.trackmywealth.backend.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.Date;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Regression test for the gap in {@code trg_transaction_append_only} (V10__transaction_ledger.sql,
 * fixed in V21__fix_transaction_append_only_trigger_gap.sql): {@code fee_amount}, {@code
 * fx_rate_to_account_currency} and {@code fx_rate_date} were not compared, so those columns could
 * be updated in place despite RULE-024/FR-TRX-007's append-only guarantee.
 *
 * <p>Runs Flyway directly against a real PostgreSQL instance rather than booting the full Spring
 * context - no JPA entities exist yet for these tables, and the trigger is pure SQL behaviour that
 * doesn't need the application layer to exercise.
 */
@Testcontainers
class TransactionAppendOnlyTriggerTest {

  @Container
  static PostgreSQLContainer<?> postgres =
      new PostgreSQLContainer<>("postgres:16")
          .withDatabaseName("trackmywealth")
          .withUsername("trackmywealth")
          .withPassword("trackmywealth");

  private static Connection connection;
  private static UUID transactionId;

  @BeforeAll
  static void migrateAndSeed() throws Exception {
    Flyway.configure()
        .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
        .load()
        .migrate();

    // The Testcontainers-provisioned role is the Postgres bootstrap (superuser) role, so it
    // bypasses row-level security regardless of V20's FORCE ROW LEVEL SECURITY - no
    // app.current_household_id session variable needs to be set for this fixture setup.
    connection =
        DriverManager.getConnection(
            postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    connection.setAutoCommit(true);

    UUID householdId =
        insertReturningId("INSERT INTO household(name) VALUES (?) RETURNING id", "Test Household");
    UUID institutionId =
        insertReturningId(
            "INSERT INTO financial_institution(household_id, name, container_currency) VALUES (?, ?, ?) RETURNING id",
            householdId,
            "Test Bank",
            "CHF");
    UUID accountId =
        insertReturningId(
            "INSERT INTO account(household_id, financial_institution_id, account_type, name, native_currency) VALUES (?, ?, ?, ?, ?) RETURNING id",
            householdId,
            institutionId,
            "CASH",
            "Test Account",
            "CHF");
    transactionId =
        insertReturningId(
            "INSERT INTO transaction(household_id, account_id, transaction_type, booking_date, amount, currency, fee_amount, fx_rate_to_account_currency, fx_rate_date) "
                + "VALUES (?, ?, 'EXPENSE', CURRENT_DATE, 45.00, 'CHF', 5.00, 1.0000000000, CURRENT_DATE) RETURNING id",
            householdId,
            accountId);
  }

  @AfterAll
  static void closeConnection() throws Exception {
    if (connection != null) {
      connection.close();
    }
  }

  @Test
  void feeAmountCannotBeUpdatedInPlace() {
    assertAppendOnlyRejects(
        "UPDATE transaction SET fee_amount = ? WHERE id = ?",
        stmt -> stmt.setBigDecimal(1, new BigDecimal("99.00")));
  }

  @Test
  void fxRateToAccountCurrencyCannotBeUpdatedInPlace() {
    assertAppendOnlyRejects(
        "UPDATE transaction SET fx_rate_to_account_currency = ? WHERE id = ?",
        stmt -> stmt.setBigDecimal(1, new BigDecimal("1.2345000000")));
  }

  @Test
  void fxRateDateCannotBeUpdatedInPlace() {
    assertAppendOnlyRejects(
        "UPDATE transaction SET fx_rate_date = ? WHERE id = ?",
        stmt -> stmt.setDate(1, Date.valueOf("2020-01-01")));
  }

  @Test
  void genuinelyMutableColumnsAreUnaffectedByTheFix() throws Exception {
    // Sanity check: the fix must not have widened the trigger to reject columns that are
    // intentionally always-mutable (category_id, notes, merchant_description, void metadata).
    try (PreparedStatement stmt =
        connection.prepareStatement("UPDATE transaction SET notes = ? WHERE id = ?")) {
      stmt.setString(1, "a correction note, not a financial-field edit");
      stmt.setObject(2, transactionId);
      assertThat(stmt.executeUpdate()).isEqualTo(1);
    }
  }

  @FunctionalInterface
  private interface FirstParameterSetter {
    void set(PreparedStatement statement) throws SQLException;
  }

  private void assertAppendOnlyRejects(String updateSql, FirstParameterSetter setValue) {
    try (PreparedStatement stmt = connection.prepareStatement(updateSql)) {
      setValue.set(stmt);
      stmt.setObject(2, transactionId);
      stmt.executeUpdate();
      fail("expected trg_transaction_append_only to reject this update");
    } catch (SQLException e) {
      assertThat(e.getSQLState()).isEqualTo("23514");
      assertThat(e.getMessage()).contains("transaction_ledger_append_only");
    }
  }

  private static UUID insertReturningId(String sql, Object... params) throws SQLException {
    try (PreparedStatement stmt = connection.prepareStatement(sql)) {
      for (int i = 0; i < params.length; i++) {
        stmt.setObject(i + 1, params[i]);
      }
      try (ResultSet rs = stmt.executeQuery()) {
        rs.next();
        return (UUID) rs.getObject("id");
      }
    }
  }
}
