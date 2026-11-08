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
 *   <li>US-29-13 (#473): two custom assets with 3,000 valuations each, a loan with 2,000 schedule
 *       entries and 3,000 repayments from the large account, a card with 3,000 proposed settlement
 *       matches, and an account whose 1,000 rows were removed (half deleted, half voided).
 *   <li>The other tenant's 20,000 proposed settlement matches: they share the queue's status, so a
 *       status-led index would read them all.
 * </ul>
 */
final class PagedListFixture {

  static final int LEDGER_ROWS = 50_000;

  // ANALYZE samples 300 rows per statistics target point: 30,000 of the 120,000 transactions by
  // default, so every run had slightly different statistics and near-equal plans won at random.
  // 1000 samples the whole table: the plans follow PostgreSQL's cost model, not luck.
  private static final String EXACT_STATISTICS = "SET default_statistics_target = 1000";
  private static final String[] TABLES = {
    "transaction",
    "import_batch",
    "account_snapshot",
    "reconciliation_result",
    "institution_catalogue",
    "custom_asset_valuation",
    "amortisation_schedule_entry",
    "loan_payment",
    "settlement_match"
  };

  /** Rows of each #473 list in the fixture's workspace. */
  static final int HISTORY_ROWS = 3_000;

  /**
   * Removed rows in the restore list's account: fewer than the other lists hold, because they also
   * sit in transaction, whose category statistics decide the ledger tests' plans.
   */
  static final int REMOVED_ROWS = 1_000;

  /** The other tenant's proposed settlement matches. */
  static final int OTHER_TENANT_ROWS = 20_000;

  /** The fixture's ids the paged reads filter on. */
  record Ids(
      UUID workspace,
      UUID account,
      UUID uncategorized,
      UUID asset,
      UUID loan,
      UUID card,
      UUID rolledBack) {}

  private PagedListFixture() {}

  static Ids seed(Connection admin) throws SQLException {
    UUID workspace =
        returningUuid(admin, "INSERT INTO workspace (name) VALUES ('Index test') RETURNING id");
    UUID account = insertAccount(admin, workspace, "Large account", "CASH");
    UUID otherAccount = insertAccount(admin, workspace, "Other account", "CASH");
    UUID otherTenant =
        returningUuid(admin, "INSERT INTO workspace (name) VALUES ('Other tenant') RETURNING id");
    UUID otherTenantAccount = insertAccount(admin, otherTenant, "Fresh import", "CASH");
    UUID uncategorized = systemCategory(admin, "UNCATEGORIZED");
    UUID groceries = systemCategory(admin, "GROCERIES");

    insertTransactions(admin, workspace, account, LEDGER_ROWS, 100, uncategorized, groceries);
    insertTransactions(admin, workspace, otherAccount, LEDGER_ROWS, 100, uncategorized, groceries);
    insertTransactions(admin, otherTenant, otherTenantAccount, 20_000, 1, uncategorized, groceries);
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
    History history = seedHistory(admin, workspace, account, groceries);
    seedOtherTenantQueue(admin, otherTenant, otherTenantAccount);
    return new Ids(
        workspace,
        account,
        uncategorized,
        history.asset(),
        history.loan(),
        history.card(),
        history.rolledBack());
  }

  private record History(UUID asset, UUID loan, UUID card, UUID rolledBack) {}

  // #473's lists in the fixture's workspace. Each date-ordered list gets distinct dates where the
  // schema demands them (valuation, schedule) and repeated ones elsewhere, so the id tie-breaker
  // takes part in the order.
  private static History seedHistory(Connection admin, UUID workspace, UUID account, UUID groceries)
      throws SQLException {
    UUID user = returningUuid(admin, REMOVER);
    UUID asset = insertAccount(admin, workspace, "House", "CUSTOM_ASSET");
    UUID otherAsset = insertAccount(admin, workspace, "Car", "CUSTOM_ASSET");
    for (UUID custom : new UUID[] {asset, otherAsset}) {
      execute(
          admin,
          "INSERT INTO custom_asset_valuation (account_id, valuation_date, value, currency)"
              + " SELECT ?, CURRENT_DATE - g, 500000 + g, 'CHF' FROM generate_series(1, ?) AS g",
          custom,
          HISTORY_ROWS);
    }
    UUID loan = insertAccount(admin, workspace, "Mortgage", "LOAN");
    execute(
        admin,
        "INSERT INTO amortisation_schedule_entry (account_id, due_date, interest_component,"
            + " principal_component, remaining_balance)"
            + " SELECT ?, CURRENT_DATE + g, 100, 900, 2000000 - 900 * g"
            + " FROM generate_series(1, 2000) AS g",
        loan);
    // Each repayment's principal row is one of the large account's ledger rows (one each).
    execute(
        admin,
        "INSERT INTO loan_payment (workspace_id, loan_account_id, paying_account_id, booking_date,"
            + " currency, total_amount, interest_amount, principal_amount, split_source,"
            + " split_estimated, principal_transaction_id, created_by)"
            + " SELECT ?, ?, account_id, booking_date, 'CHF', 1000, 100, 900, 'STATED', FALSE, id, ?"
            + " FROM transaction WHERE account_id = ? ORDER BY id LIMIT ?",
        workspace,
        loan,
        user,
        account,
        HISTORY_ROWS);
    UUID card = insertAccount(admin, workspace, "Card", "CREDIT_CARD");
    insertProposedMatches(admin, card, account, HISTORY_ROWS);
    UUID rolledBack = insertAccount(admin, workspace, "Rolled back", "CASH");
    insertRemovedTransactions(admin, workspace, rolledBack, REMOVED_ROWS, groceries, user);
    return new History(asset, loan, card, rolledBack);
  }

  // The other tenant's queue lives in settlement_match only, so the transaction table's
  // statistics - which the ledger tests' plans follow - are not changed by it.
  private static void seedOtherTenantQueue(
      Connection admin, UUID otherTenant, UUID otherTenantAccount) throws SQLException {
    UUID otherCard = insertAccount(admin, otherTenant, "Other card", "CREDIT_CARD");
    insertProposedMatches(admin, otherCard, otherTenantAccount, OTHER_TENANT_ROWS);
  }

  private static final String REMOVER =
      "INSERT INTO app_user (email, password_hash) VALUES ('index-fixture@example.test', 'x')"
          + " RETURNING id";

  // One-sided proposals: a match needs only a payment row (and no card row).
  private static void insertProposedMatches(Connection admin, UUID card, UUID paying, int count)
      throws SQLException {
    execute(
        admin,
        "INSERT INTO settlement_match (workspace_id, card_account_id, payment_transaction_id,"
            + " status, match_basis, created_at)"
            + " SELECT workspace_id, ?, id, 'PROPOSED', 'BALANCE_EQUALS_PAYMENT',"
            + " now() - (row_number() OVER (ORDER BY id) * INTERVAL '1 minute')"
            + " FROM transaction WHERE account_id = ? ORDER BY id DESC LIMIT ?",
        card,
        paying,
        count);
  }

  // Removed within the 30-day restore window, every other row voided, the others deleted. They are
  // in category, as every ledger row is, so the category statistics stay as the ledger tests need.
  private static void insertRemovedTransactions(
      Connection admin, UUID owner, UUID target, int count, UUID category, UUID remover)
      throws SQLException {
    execute(
        admin,
        "INSERT INTO transaction (workspace_id, account_id, transaction_type, booking_date,"
            + " amount, currency, category_id, deleted_at, deleted_by, voided_at, void_reason)"
            + " SELECT ?, ?, 'EXPENSE', CURRENT_DATE - (g % 365), -1 - (g % 200), 'CHF', ?,"
            + " CASE WHEN g % 2 = 1 THEN now() - (g * INTERVAL '10 seconds') END,"
            + " CASE WHEN g % 2 = 1 THEN ?::UUID END,"
            + " CASE WHEN g % 2 = 0 THEN now() - (g * INTERVAL '10 seconds') END,"
            + " CASE WHEN g % 2 = 0 THEN 'Import rolled back' END"
            + " FROM generate_series(1, ?) AS g",
        owner,
        target,
        category,
        remover,
        count);
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

  private static UUID insertAccount(Connection admin, UUID owner, String name, String type)
      throws SQLException {
    return returningUuid(
        admin,
        "INSERT INTO account (workspace_id, portfolio_id, account_type, name,"
            + " native_currency) SELECT ?, id, ?, ?, 'CHF'"
            + " FROM portfolio WHERE workspace_id = ? RETURNING id",
        owner,
        type,
        name,
        owner);
  }

  private static UUID systemCategory(Connection admin, String code) throws SQLException {
    return returningUuid(
        admin, "SELECT id FROM category WHERE workspace_id IS NULL AND code = ?", code);
  }

  // Every inboxEvery-th row is uncategorized, the others are in category.
  private static void insertTransactions(
      Connection admin,
      UUID owner,
      UUID target,
      int count,
      int inboxEvery,
      UUID inbox,
      UUID category)
      throws SQLException {
    execute(
        admin,
        "INSERT INTO transaction (workspace_id, account_id, transaction_type, booking_date,"
            + " amount, currency, category_id, created_at)"
            + " SELECT ?, ?, 'EXPENSE', CURRENT_DATE - (g % 365), (g % 200) - 100, 'CHF',"
            + " CASE WHEN g % ? = 0 THEN ?::UUID ELSE ?::UUID END,"
            + " now() - (g * INTERVAL '1 second')"
            + " FROM generate_series(1, ?) AS g",
        owner,
        target,
        inboxEvery,
        inbox,
        category,
        count);
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
