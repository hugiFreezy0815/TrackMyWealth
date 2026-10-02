package com.trackmywealth.backend.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.LocalDate;
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
 * #223's migrations, applied to a database that already holds data: V52 seeds the earliest booking
 * date from the existing ledger and keeps it current through a trigger on {@code transaction}; V53
 * moves FX rates before 2023 out of {@code fx_rate_default} into their own partition; V54 seeds the
 * currencies in use and keeps them current through triggers. Runs Flyway directly, like {@code
 * TransactionAppendOnlyTriggerTest}: both are pure SQL behaviour.
 */
@Testcontainers
class FxRateHistoryMigrationTest {

  @Container
  static PostgreSQLContainer<?> postgres =
      new PostgreSQLContainer<>("postgres:16")
          .withDatabaseName("trackmywealth")
          .withUsername("trackmywealth")
          .withPassword("trackmywealth");

  private static Connection connection;
  private static UUID workspaceId;
  private static UUID accountId;

  @BeforeAll
  static void migrateWithExistingData() throws Exception {
    flyway("51").migrate();
    // The Testcontainers role is the bootstrap superuser: it bypasses row-level security, as the
    // migration role does in every environment today (see V52's header).
    connection =
        DriverManager.getConnection(
            postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    connection.setAutoCommit(true);

    workspaceId = insertReturningId("INSERT INTO workspace(name) VALUES (?) RETURNING id", "Home");
    UUID institutionId =
        insertReturningId(
            "INSERT INTO financial_institution(workspace_id, name, container_currency)"
                + " VALUES (?, ?, ?) RETURNING id",
            workspaceId,
            "Bank",
            "CHF");
    accountId =
        insertReturningId(
            "INSERT INTO account(workspace_id, financial_institution_id, account_type, name,"
                + " native_currency) VALUES (?, ?, ?, ?, ?) RETURNING id",
            workspaceId,
            institutionId,
            "CASH",
            "Current account",
            "CHF");
    book(LocalDate.of(2024, 5, 1));
    book(LocalDate.of(2023, 11, 20));
    // A hand-entered rate before 2023, in fx_rate_default until V53.
    execute(
        "INSERT INTO fx_rate(base_currency, quote_currency, rate_date, rate, source)"
            + " VALUES ('EUR', 'CHF', DATE '2019-03-01', 1.1350000000, 'MANUAL')");

    flyway(null).migrate();
  }

  @AfterAll
  static void closeConnection() throws Exception {
    if (connection != null) {
      connection.close();
    }
  }

  @Test
  void historyRequirementIsSeededFromTheExistingLedgerAndFollowsOlderBookingsOnly()
      throws Exception {
    assertThat(earliestBooking()).isEqualTo(LocalDate.of(2023, 11, 20));

    book(LocalDate.of(2025, 1, 15));
    assertThat(earliestBooking()).isEqualTo(LocalDate.of(2023, 11, 20));

    book(LocalDate.of(2021, 2, 3));
    assertThat(earliestBooking()).isEqualTo(LocalDate.of(2021, 2, 3));
  }

  @Test
  void historyRequirementHoldsExactlyOneRow() throws Exception {
    try (PreparedStatement statement =
        connection.prepareStatement("INSERT INTO fx_rate_history_requirement DEFAULT VALUES")) {
      assertThatThrownBy(statement::executeUpdate)
          .hasMessageContaining("fx_rate_history_requirement_singleton_key");
    }
  }

  @Test
  void ratesBefore2023LeaveTheDefaultPartition() throws Exception {
    try (PreparedStatement statement =
            connection.prepareStatement(
                "SELECT tableoid::regclass::text, rate FROM fx_rate WHERE rate_date = ?");
        PreparedStatement defaultCount =
            connection.prepareStatement("SELECT count(*) FROM fx_rate_default")) {
      statement.setObject(1, LocalDate.of(2019, 3, 1));
      try (ResultSet rs = statement.executeQuery()) {
        assertThat(rs.next()).isTrue();
        assertThat(rs.getString(1)).isEqualTo("fx_rate_pre_2023");
        assertThat(rs.getBigDecimal(2)).isEqualByComparingTo("1.135");
      }
      try (ResultSet rs = defaultCount.executeQuery()) {
        rs.next();
        assertThat(rs.getLong(1)).isZero();
      }
    }
    // A backfilled rate from the ECB's first year lands there too.
    execute(
        "INSERT INTO fx_rate(base_currency, quote_currency, rate_date, rate, source)"
            + " VALUES ('EUR', 'USD', DATE '1999-01-04', 1.1789000000, 'ECB')");
    try (PreparedStatement statement =
        connection.prepareStatement(
            "SELECT tableoid::regclass::text FROM fx_rate WHERE rate_date = DATE '1999-01-04'")) {
      try (ResultSet rs = statement.executeQuery()) {
        rs.next();
        assertThat(rs.getString(1)).isEqualTo("fx_rate_pre_2023");
      }
    }
  }

  @Test
  void currenciesInUseAreSeededFromTheExistingDataAndFollowNewOnesOnce() throws Exception {
    assertThat(currenciesInUse()).contains("CHF");

    insertReturningId(
        "INSERT INTO account(workspace_id, financial_institution_id, account_type, name,"
            + " native_currency) SELECT workspace_id, financial_institution_id, 'SAVINGS',"
            + " 'Dollar savings', 'USD' FROM account WHERE id = ? RETURNING id",
        accountId);
    book(LocalDate.of(2025, 2, 3), "GBP");
    book(LocalDate.of(2025, 2, 4), "GBP");

    assertThat(currenciesInUse()).contains("CHF", "USD", "GBP").doesNotHaveDuplicates();
    try (PreparedStatement statement =
            connection.prepareStatement(
                "SELECT cross_rates_derived FROM fx_rate_currency_in_use WHERE currency = 'GBP'");
        ResultSet rs = statement.executeQuery()) {
      assertThat(rs.next()).isTrue();
      assertThat(rs.getBoolean(1)).isFalse(); // the import derives its cross rates next
    }
  }

  @Test
  void aPendingTransferDetectionIsOneEntryPerWorkspaceAndDate() throws Exception {
    String record =
        "INSERT INTO transfer_detection_fx_pending (workspace_id, booking_date)"
            + " VALUES ('"
            + workspaceId
            + "', DATE '2025-03-03') ON CONFLICT (workspace_id, booking_date) DO NOTHING";
    execute(record);
    execute(record);

    try (PreparedStatement statement =
            connection.prepareStatement("SELECT count(*) FROM transfer_detection_fx_pending");
        ResultSet rs = statement.executeQuery()) {
      rs.next();
      assertThat(rs.getLong(1)).isEqualTo(1);
    }
  }

  private static List<String> currenciesInUse() throws Exception {
    List<String> currencies = new ArrayList<>();
    try (PreparedStatement statement =
            connection.prepareStatement("SELECT currency FROM fx_rate_currency_in_use");
        ResultSet rs = statement.executeQuery()) {
      while (rs.next()) {
        currencies.add(rs.getString(1));
      }
    }
    return currencies;
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

  private static void book(LocalDate bookingDate) throws Exception {
    book(bookingDate, "CHF");
  }

  private static void book(LocalDate bookingDate, String currency) throws Exception {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "INSERT INTO transaction(workspace_id, account_id, transaction_type, booking_date,"
                + " amount, currency) VALUES (?, ?, 'DEPOSIT', ?, 100.00, ?)")) {
      statement.setObject(1, workspaceId);
      statement.setObject(2, accountId);
      statement.setObject(3, bookingDate);
      statement.setString(4, currency);
      statement.executeUpdate();
    }
  }

  private static LocalDate earliestBooking() throws Exception {
    try (PreparedStatement statement =
            connection.prepareStatement(
                "SELECT earliest_booking_date FROM fx_rate_history_requirement");
        ResultSet rs = statement.executeQuery()) {
      assertThat(rs.next()).isTrue();
      LocalDate date = rs.getObject(1, LocalDate.class);
      assertThat(rs.next()).isFalse();
      return date;
    }
  }

  private static void execute(String sql) throws Exception {
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.executeUpdate();
    }
  }

  private static UUID insertReturningId(String sql, Object... params) throws Exception {
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      for (int i = 0; i < params.length; i++) {
        statement.setObject(i + 1, params[i]);
      }
      try (ResultSet rs = statement.executeQuery()) {
        rs.next();
        return rs.getObject(1, UUID.class);
      }
    }
  }
}
