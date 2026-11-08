package com.trackmywealth.backend.db;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;

/**
 * US-29-06 (#395): the synthetic data behind the paged-list index tests, seeded over JDBC as the
 * Testcontainers superuser. No real financial data and no 50,000 HTTP requests: one generated,
 * deterministic data set.
 *
 * <ul>
 *   <li>One account with 50,000 transactions, one row in a hundred uncategorized - a worked-through
 *       inbox on a long history.
 *   <li>A second, equally large account in the same workspace. Under RLS the policy's workspace_id
 *       condition can drive V114's workspace-wide indexes; with one dominant account they would
 *       look like a per-account index.
 *   <li>Another tenant's 20,000-row import, all uncategorized. UNCATEGORIZED is one system category
 *       shared by every workspace, so these rows fill {@code idx_transaction_category}.
 *   <li>500 import batches, 500 snapshots with a cash reconciliation result each, and 500 active
 *       catalogue entries.
 * </ul>
 */
final class PagedListFixture {

  static final int LEDGER_ROWS = 50_000;

  // ANALYZE samples 300 rows per statistics target point: 30,000 of the 120,000 transactions by
  // default, so every run had slightly different statistics and near-equal plans won at random.
  // 1000 samples the whole table: the plans follow PostgreSQL's cost model, not luck.
  private static final String EXACT_STATISTICS = "SET default_statistics_target = 1000";
  private static final String[] TABLES = {
    "transaction", "import_batch", "reconciliation_result", "institution_catalogue"
  };

  /** The fixture's ids the paged reads filter on. */
  record Ids(UUID workspace, UUID account, UUID uncategorized) {}

  private PagedListFixture() {}

  static Ids seed(Connection admin) throws SQLException {
    UUID workspace =
        returningUuid(admin, "INSERT INTO workspace (name) VALUES ('Index test') RETURNING id");
    UUID account = insertAccount(admin, workspace, "Large account");
    UUID otherAccount = insertAccount(admin, workspace, "Other account");
    UUID otherTenant =
        returningUuid(admin, "INSERT INTO workspace (name) VALUES ('Other tenant') RETURNING id");
    UUID otherTenantAccount = insertAccount(admin, otherTenant, "Fresh import");
    UUID uncategorized = systemCategory(admin, "UNCATEGORIZED");
    UUID groceries = systemCategory(admin, "GROCERIES");

    insertInterleavedTransactions(
        admin,
        uncategorized,
        groceries,
        new Ledger(workspace, account, LEDGER_ROWS, 100),
        new Ledger(workspace, otherAccount, LEDGER_ROWS, 100),
        new Ledger(otherTenant, otherTenantAccount, 20_000, 1));
    execute(
        admin,
        "INSERT INTO import_batch (workspace_id, account_id, uploaded_at)"
            + " SELECT ?, ?, now() - (g * INTERVAL '1 minute')"
            + " FROM generate_series(1, 500) AS g",
        workspace,
        account);
    execute(
        admin,
        "INSERT INTO account_snapshot (workspace_id, account_id, snapshot_date, currency)"
            + " SELECT ?, ?, CURRENT_DATE - g, 'CHF'"
            + " FROM generate_series(1, 500) AS g",
        workspace,
        account);
    execute(
        admin,
        "INSERT INTO reconciliation_result (workspace_id, account_id, snapshot_id, created_at)"
            + " SELECT ?, ?, id, now() - (row_number() OVER (ORDER BY snapshot_date)"
            + " * INTERVAL '1 minute') FROM account_snapshot WHERE account_id = ?",
        workspace,
        account,
        account);
    execute(
        admin,
        "INSERT INTO institution_catalogue (name, country, institution_type)"
            + " SELECT 'Index fixture ' || lpad(g::TEXT, 5, '0'), 'CH', 'BANK'"
            + " FROM generate_series(1, 500) AS g");
    return new Ids(workspace, account, uncategorized);
  }

  /**
   * Exact statistics and a current visibility map for the fixture's tables, as autovacuum would
   * leave them: without the latter no index-only scan is planned. Needs autocommit (VACUUM).
   */
  static void settle(Connection admin) throws SQLException {
    try (Statement statement = admin.createStatement()) {
      statement.execute(EXACT_STATISTICS);
      for (String table : TABLES) {
        statement.execute("VACUUM (ANALYZE) " + table);
      }
    }
  }

  private static UUID insertAccount(Connection admin, UUID owner, String name) throws SQLException {
    return returningUuid(
        admin,
        "INSERT INTO account (workspace_id, portfolio_id, account_type, name,"
            + " native_currency) SELECT ?, id, 'CASH', ?, 'CHF'"
            + " FROM portfolio WHERE workspace_id = ? RETURNING id",
        owner,
        name,
        owner);
  }

  private static UUID systemCategory(Connection admin, String code) throws SQLException {
    return returningUuid(
        admin, "SELECT id FROM category WHERE workspace_id IS NULL AND code = ?", code);
  }

  /** One account's generated rows: every inboxEvery-th of them is uncategorized. */
  private record Ledger(UUID workspace, UUID account, int rows, int inboxEvery) {}

  // The ledgers' rows go into the heap interleaved, each spread evenly over the whole table (ties
  // broken by the ledger's position here, never by an id). Inserted one ledger after the other,
  // the random account, workspace and category UUIDs decided how the heap order lined up with the
  // indexes' order: pg_stats' correlation for account_id ranged from -0.70 to 1, and with it the
  // index scan costs, so idx_transaction_account_category_order lost its near-tie at random (CI
  // on #505 and #509; one local run in six). Interleaved, as concurrent imports leave a real table,
  // the layout no longer depends on which UUIDs a run drew.
  private static void insertInterleavedTransactions(
      Connection admin, UUID inbox, UUID category, Ledger... ledgers) throws SQLException {
    StringBuilder values = new StringBuilder();
    Object[] parameters = new Object[2 + ledgers.length * 4];
    parameters[0] = inbox;
    parameters[1] = category;
    for (int i = 0; i < ledgers.length; i++) {
      values.append(i == 0 ? "" : ", ").append("(").append(i).append(", ?::UUID, ?::UUID, ?, ?)");
      parameters[2 + i * 4] = ledgers[i].workspace();
      parameters[3 + i * 4] = ledgers[i].account();
      parameters[4 + i * 4] = ledgers[i].rows();
      parameters[5 + i * 4] = ledgers[i].inboxEvery();
    }
    execute(
        admin,
        "INSERT INTO transaction (workspace_id, account_id, transaction_type, booking_date,"
            + " amount, currency, category_id, created_at)"
            + " SELECT l.workspace_id, l.account_id, 'EXPENSE', CURRENT_DATE - (g % 365),"
            + " (g % 200) - 100, 'CHF',"
            + " CASE WHEN g % l.inbox_every = 0 THEN ?::UUID ELSE ?::UUID END,"
            + " now() - (g * INTERVAL '1 second')"
            + " FROM (VALUES "
            + values
            + ") AS l (position, workspace_id, account_id, row_count, inbox_every)"
            + " CROSS JOIN LATERAL generate_series(1, l.row_count) AS g"
            + " ORDER BY g::float8 / l.row_count, l.position",
        parameters);
  }

  private static UUID returningUuid(Connection admin, String sql, Object... parameters)
      throws SQLException {
    try (PreparedStatement statement = admin.prepareStatement(sql)) {
      bind(statement, parameters);
      try (ResultSet rows = statement.executeQuery()) {
        if (!rows.next()) {
          throw new IllegalStateException("No row from: " + sql);
        }
        return rows.getObject(1, UUID.class);
      }
    }
  }

  private static void execute(Connection admin, String sql, Object... parameters)
      throws SQLException {
    try (PreparedStatement statement = admin.prepareStatement(sql)) {
      bind(statement, parameters);
      statement.executeUpdate();
    }
  }

  static void bind(PreparedStatement statement, Object... parameters) throws SQLException {
    for (int i = 0; i < parameters.length; i++) {
      statement.setObject(i + 1, parameters[i]);
    }
  }
}
