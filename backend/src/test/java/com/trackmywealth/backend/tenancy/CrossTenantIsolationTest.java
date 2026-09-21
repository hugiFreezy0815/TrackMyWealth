package com.trackmywealth.backend.tenancy;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * US-28-04: a living cross-tenant isolation suite (FR-TEN-010) - grows alongside every
 * entity-introducing epic. No API exists yet to create a second workspace (setup is a one-time
 * bootstrap; see issue #47 for admin-created users never getting a workspace link either) and no
 * controller exposes a workspace-scoped resource by id yet (issue #41) - so this suite tests the
 * mechanism that actually prevents cross-tenant leakage (the RLS policies in {@code
 * V20__tenancy_row_level_security.sql}) directly via raw JDBC, bootstrapping two independent
 * workspaces the same low-level way {@code SetupService} does internally.
 *
 * <p>Runs Flyway directly against a real PostgreSQL instance rather than booting the full Spring
 * context, matching {@code TransactionAppendOnlyTriggerTest}'s established pattern for pure-SQL
 * behaviour - no application layer is needed to exercise RLS policies.
 *
 * <p><b>Critical detail this test exists to get right:</b> the Testcontainers-provisioned bootstrap
 * role is a PostgreSQL superuser, which always bypasses row-level security regardless of V20's
 * {@code FORCE ROW LEVEL SECURITY} (see {@code TransactionAppendOnlyTriggerTest}'s own note on
 * this). Every assertion in this class therefore runs as a dedicated, non-superuser, non-BYPASSRLS
 * role created in {@link #createNonSuperuserRoleForRlsEnforcement()}, granted exactly the
 * privileges V20's commented-out future-runtime-role block sketches ({@code trackmywealth_runtime})
 * - anything less would silently pass even if the RLS policies were completely broken.
 *
 * <p>Uses plain JDBC rather than the JPA repositories for fixture setup too: a row inserted for
 * workspace B would otherwise sit in the same test's Hibernate first-level cache, so a later lookup
 * under workspace A's context could return a cached managed instance without ever re-querying the
 * database - silently bypassing the exact mechanism this suite exists to verify.
 */
@Testcontainers
class CrossTenantIsolationTest {

  @Container
  static PostgreSQLContainer<?> postgres =
      new PostgreSQLContainer<>("postgres:16")
          .withDatabaseName("trackmywealth")
          .withUsername("trackmywealth")
          .withPassword("trackmywealth");

  private static final String TEST_ROLE = "cross_tenant_test_role";
  private static final String TEST_ROLE_PASSWORD = "cross-tenant-test-role-password";

  // US-28-04's own "lightweight registry/checklist" (its exact wording): every RLS-protected
  // table that has a mapped JPA entity must appear here, backed by an actual cross-tenant test
  // method below. everyRlsProtectedTableWithAMappedEntityHasARegisteredCrossTenantTest fails the
  // moment a future PR adds a new @Entity for an RLS-protected table without adding both an entry
  // here and a corresponding test in the same PR - this IS the enforcement mechanism, not just
  // documentation of one.
  private static final Set<String> COVERED_TABLES =
      Set.of(
          "workspace",
          "workspace_member",
          "financial_institution",
          "account",
          "sharing_grant",
          "transaction",
          "settlement_match");

  private UUID workspaceAId;
  private UUID workspaceBId;
  private UUID memberAId;
  private UUID memberBId;
  private UUID institutionAId;
  private UUID institutionBId;
  private UUID accountAId;
  private UUID transactionAId;
  private UUID transactionBId;
  private UUID settlementMatchAId;
  private UUID settlementMatchBId;
  private UUID accountBId;
  private UUID sharingGrantAId;
  private UUID sharingGrantBId;

  @BeforeAll
  static void migrateAndCreateNonSuperuserRole() throws Exception {
    Flyway.configure()
        .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
        .load()
        .migrate();
    createNonSuperuserRoleForRlsEnforcement();
  }

  // Mirrors V20__tenancy_row_level_security.sql's commented-out `trackmywealth_runtime` grant
  // block exactly, so this test enforces RLS under the same privilege level the real application
  // is meant to run under once that role split lands (see ADR-0001's Consequences section).
  private static void createNonSuperuserRoleForRlsEnforcement() throws Exception {
    try (Connection admin = adminConnection();
        Statement statement = admin.createStatement()) {
      statement.execute(
          "CREATE ROLE "
              + TEST_ROLE
              + " LOGIN PASSWORD '"
              + TEST_ROLE_PASSWORD
              + "' NOSUPERUSER NOBYPASSRLS");
      statement.execute(
          "GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO " + TEST_ROLE);
      statement.execute("GRANT USAGE ON ALL SEQUENCES IN SCHEMA public TO " + TEST_ROLE);
    }
  }

  @BeforeEach
  void bootstrapTwoIndependentWorkspaces() throws Exception {
    try (Connection admin = adminConnection();
        Statement statement = admin.createStatement()) {
      statement.execute(
          "TRUNCATE TABLE settlement_match, transaction, sharing_grant, account, financial_institution,"
              + " workspace_member, workspace RESTART IDENTITY CASCADE");
    }

    try (Connection connection = testRoleConnection()) {
      connection.setAutoCommit(false);

      workspaceAId = UUID.randomUUID();
      setWorkspaceContext(connection, workspaceAId);
      insertWorkspace(connection, workspaceAId, "Cross-Tenant Test Workspace A");
      memberAId = insertMember(connection, workspaceAId, "Member A");
      institutionAId = personalAssetsId(connection, workspaceAId);
      accountAId = insertAccount(connection, workspaceAId, institutionAId, "Account A");
      transactionAId = insertTransaction(connection, workspaceAId, accountAId);
      settlementMatchAId =
          insertSettlementMatch(connection, workspaceAId, accountAId, transactionAId);
      sharingGrantAId = insertSharingGrant(connection, workspaceAId, memberAId);

      workspaceBId = UUID.randomUUID();
      setWorkspaceContext(connection, workspaceBId);
      insertWorkspace(connection, workspaceBId, "Cross-Tenant Test Workspace B");
      memberBId = insertMember(connection, workspaceBId, "Member B");
      institutionBId = personalAssetsId(connection, workspaceBId);
      accountBId = insertAccount(connection, workspaceBId, institutionBId, "Account B");
      transactionBId = insertTransaction(connection, workspaceBId, accountBId);
      settlementMatchBId =
          insertSettlementMatch(connection, workspaceBId, accountBId, transactionBId);
      sharingGrantBId = insertSharingGrant(connection, workspaceBId, memberBId);

      connection.commit();
    }
  }

  @Test
  void workspaceRowIsInvisibleAcrossWorkspaces() throws Exception {
    assertThat(rowVisibleUnderContext(workspaceAId, "workspace", workspaceBId)).isFalse();
    assertThat(rowVisibleUnderContext(workspaceBId, "workspace", workspaceAId)).isFalse();
  }

  @Test
  void workspaceMemberRowIsInvisibleAcrossWorkspaces() throws Exception {
    assertThat(rowVisibleUnderContext(workspaceAId, "workspace_member", memberBId)).isFalse();
    assertThat(rowVisibleUnderContext(workspaceBId, "workspace_member", memberAId)).isFalse();
  }

  @Test
  void financialInstitutionRowIsInvisibleAcrossWorkspaces() throws Exception {
    assertThat(rowVisibleUnderContext(workspaceAId, "financial_institution", institutionBId))
        .isFalse();
    assertThat(rowVisibleUnderContext(workspaceBId, "financial_institution", institutionAId))
        .isFalse();
  }

  @Test
  void accountRowIsInvisibleAcrossWorkspaces() throws Exception {
    assertThat(rowVisibleUnderContext(workspaceAId, "account", accountBId)).isFalse();
    assertThat(rowVisibleUnderContext(workspaceBId, "account", accountAId)).isFalse();
  }

  // US-03-03: sharing_grant is RLS-protected the same way account/financial_institution are
  // (carries its own workspace_id, see V20) - a workspace must never see another workspace's
  // grants, the same isolation boundary the grant/revoke mechanism itself relies on.
  @Test
  void sharingGrantRowIsInvisibleAcrossWorkspaces() throws Exception {
    assertThat(rowVisibleUnderContext(workspaceAId, "sharing_grant", sharingGrantBId)).isFalse();
    assertThat(rowVisibleUnderContext(workspaceBId, "sharing_grant", sharingGrantAId)).isFalse();
  }

  // US-09-01: transaction carries its own workspace_id and is RLS-protected the same way (V20) - a
  // ledger row, including a card purchase's amount and merchant, must never cross a workspace.
  @Test
  void transactionRowIsInvisibleAcrossWorkspaces() throws Exception {
    assertThat(rowVisibleUnderContext(workspaceAId, "transaction", transactionBId)).isFalse();
    assertThat(rowVisibleUnderContext(workspaceBId, "transaction", transactionAId)).isFalse();
  }

  // US-09-02: settlement_match carries its own workspace_id and is RLS-protected the same way - a
  // workspace must never see which of another workspace's payments were matched to which card.
  @Test
  void settlementMatchRowIsInvisibleAcrossWorkspaces() throws Exception {
    assertThat(rowVisibleUnderContext(workspaceAId, "settlement_match", settlementMatchBId))
        .isFalse();
    assertThat(rowVisibleUnderContext(workspaceBId, "settlement_match", settlementMatchAId))
        .isFalse();
  }

  // A cross-tenant suite that only ever asserts "denied" can pass vacuously if RLS is
  // accidentally denying everyone, including a workspace reading its own data - this is the
  // false-negative guard against that.
  @Test
  void eachWorkspaceCanStillSeeItsOwnData() throws Exception {
    assertThat(rowVisibleUnderContext(workspaceAId, "workspace", workspaceAId)).isTrue();
    assertThat(rowVisibleUnderContext(workspaceAId, "workspace_member", memberAId)).isTrue();
    assertThat(rowVisibleUnderContext(workspaceAId, "financial_institution", institutionAId))
        .isTrue();
    assertThat(rowVisibleUnderContext(workspaceAId, "account", accountAId)).isTrue();
    assertThat(rowVisibleUnderContext(workspaceAId, "sharing_grant", sharingGrantAId)).isTrue();
    assertThat(rowVisibleUnderContext(workspaceAId, "transaction", transactionAId)).isTrue();
    assertThat(rowVisibleUnderContext(workspaceAId, "settlement_match", settlementMatchAId))
        .isTrue();
    assertThat(rowVisibleUnderContext(workspaceBId, "workspace", workspaceBId)).isTrue();
    assertThat(rowVisibleUnderContext(workspaceBId, "workspace_member", memberBId)).isTrue();
    assertThat(rowVisibleUnderContext(workspaceBId, "financial_institution", institutionBId))
        .isTrue();
    assertThat(rowVisibleUnderContext(workspaceBId, "account", accountBId)).isTrue();
    assertThat(rowVisibleUnderContext(workspaceBId, "sharing_grant", sharingGrantBId)).isTrue();
    assertThat(rowVisibleUnderContext(workspaceBId, "transaction", transactionBId)).isTrue();
    assertThat(rowVisibleUnderContext(workspaceBId, "settlement_match", settlementMatchBId))
        .isTrue();
  }

  @Test
  void everyRlsProtectedTableWithAMappedEntityHasARegisteredCrossTenantTest() throws Exception {
    Set<String> rlsProtectedTables = rlsProtectedTableNames();
    Set<String> mappedEntityTables = mappedEntityTableNames();
    Set<String> entityBackedRlsTables = new HashSet<>(rlsProtectedTables);
    entityBackedRlsTables.retainAll(mappedEntityTables);

    assertThat(entityBackedRlsTables)
        .as(
            "FR-TEN-010: every RLS-protected table (V20) that now has a mapped JPA entity must"
                + " have a corresponding cross-tenant test added to this class IN THE SAME PR that"
                + " introduced the entity - add both a test method and an entry in COVERED_TABLES")
        .isSubsetOf(COVERED_TABLES);
  }

  private static Connection adminConnection() throws Exception {
    return DriverManager.getConnection(
        postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
  }

  private static Connection testRoleConnection() throws Exception {
    return DriverManager.getConnection(postgres.getJdbcUrl(), TEST_ROLE, TEST_ROLE_PASSWORD);
  }

  private void setWorkspaceContext(Connection connection, UUID workspaceId) throws Exception {
    try (PreparedStatement statement =
        connection.prepareStatement("SELECT set_config('app.current_workspace_id', ?, true)")) {
      statement.setString(1, workspaceId.toString());
      statement.execute();
    }
  }

  private void insertWorkspace(Connection connection, UUID workspaceId, String name)
      throws Exception {
    try (PreparedStatement statement =
        connection.prepareStatement("INSERT INTO workspace (id, name) VALUES (?, ?)")) {
      statement.setObject(1, workspaceId);
      statement.setString(2, name);
      statement.executeUpdate();
    }
  }

  private UUID insertMember(Connection connection, UUID workspaceId, String displayName)
      throws Exception {
    UUID memberId = UUID.randomUUID();
    try (PreparedStatement statement =
        connection.prepareStatement(
            "INSERT INTO workspace_member (id, workspace_id, display_name) VALUES (?, ?, ?)")) {
      statement.setObject(1, memberId);
      statement.setObject(2, workspaceId);
      statement.setString(3, displayName);
      statement.executeUpdate();
    }
    return memberId;
  }

  // The "Personal Assets" container is created by V19's AFTER INSERT trigger on `workspace`, not
  // by this test - looked up under the same transaction/context the workspace was just created
  // in, since a plain query with no context set would see zero rows under RLS (workspace_id never
  // equals NULL), not the row this test needs.
  private UUID personalAssetsId(Connection connection, UUID workspaceId) throws Exception {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "SELECT id FROM financial_institution WHERE workspace_id = ? AND"
                + " is_personal_assets_default = true")) {
      statement.setObject(1, workspaceId);
      try (ResultSet resultSet = statement.executeQuery()) {
        assertThat(resultSet.next())
            .as("V19's personal-assets-container trigger must have fired for " + workspaceId)
            .isTrue();
        return (UUID) resultSet.getObject("id");
      }
    }
  }

  private UUID insertAccount(
      Connection connection, UUID workspaceId, UUID financialInstitutionId, String name)
      throws Exception {
    UUID accountId = UUID.randomUUID();
    try (PreparedStatement statement =
        connection.prepareStatement(
            "INSERT INTO account (id, workspace_id, financial_institution_id, account_type,"
                + " name, native_currency) VALUES (?, ?, ?, 'CASH', ?, 'CHF')")) {
      statement.setObject(1, accountId);
      statement.setObject(2, workspaceId);
      statement.setObject(3, financialInstitutionId);
      statement.setString(4, name);
      statement.executeUpdate();
    }
    return accountId;
  }

  private UUID insertTransaction(Connection connection, UUID workspaceId, UUID accountId)
      throws Exception {
    UUID transactionId = UUID.randomUUID();
    try (PreparedStatement statement =
        connection.prepareStatement(
            "INSERT INTO transaction (id, workspace_id, account_id, transaction_type,"
                + " booking_date, amount, currency) VALUES (?, ?, ?, 'EXPENSE', CURRENT_DATE,"
                + " -1.00, 'CHF')")) {
      statement.setObject(1, transactionId);
      statement.setObject(2, workspaceId);
      statement.setObject(3, accountId);
      statement.executeUpdate();
    }
    return transactionId;
  }

  // A one-sided candidate: only the payment leg exists, so card_transaction_id stays NULL.
  private UUID insertSettlementMatch(
      Connection connection, UUID workspaceId, UUID cardAccountId, UUID paymentTransactionId)
      throws Exception {
    UUID matchId = UUID.randomUUID();
    try (PreparedStatement statement =
        connection.prepareStatement(
            "INSERT INTO settlement_match (id, workspace_id, card_account_id,"
                + " payment_transaction_id, status, match_basis) VALUES (?, ?, ?, ?, 'PROPOSED',"
                + " 'BALANCE_EQUALS_PAYMENT')")) {
      statement.setObject(1, matchId);
      statement.setObject(2, workspaceId);
      statement.setObject(3, cardAccountId);
      statement.setObject(4, paymentTransactionId);
      statement.executeUpdate();
    }
    return matchId;
  }

  // WORKSPACE scope needs no scope_account_id/scope_institution_id (V6's own CHECK constraint) -
  // the simplest shape that still exercises sharing_grant's own workspace_id RLS policy, which is
  // all this suite cares about; granting a member access to their own workspace isn't meaningful
  // product behaviour, just a minimal valid row.
  private UUID insertSharingGrant(Connection connection, UUID workspaceId, UUID memberId)
      throws Exception {
    UUID grantId = UUID.randomUUID();
    try (PreparedStatement statement =
        connection.prepareStatement(
            "INSERT INTO sharing_grant (id, workspace_id, granted_to_member_id, scope_type,"
                + " access_level, granted_by_member_id) VALUES (?, ?, ?, 'WORKSPACE', 'READ',"
                + " ?)")) {
      statement.setObject(1, grantId);
      statement.setObject(2, workspaceId);
      statement.setObject(3, memberId);
      statement.setObject(4, memberId);
      statement.executeUpdate();
    }
    return grantId;
  }

  // `table` only ever comes from this class's own small set of hardcoded constant call sites
  // (never external input), so the string-built SQL below is safe despite not being parameterized
  // - PreparedStatement placeholders can't parameterize an identifier, only a value.
  private boolean rowVisibleUnderContext(UUID contextWorkspaceId, String table, UUID targetRowId)
      throws Exception {
    try (Connection connection = testRoleConnection()) {
      connection.setAutoCommit(false);
      setWorkspaceContext(connection, contextWorkspaceId);
      try (PreparedStatement statement =
          connection.prepareStatement("SELECT 1 FROM " + table + " WHERE id = ?")) {
        statement.setObject(1, targetRowId);
        try (ResultSet resultSet = statement.executeQuery()) {
          boolean visible = resultSet.next();
          connection.rollback();
          return visible;
        }
      }
    }
  }

  // Queries pg_class.relrowsecurity directly - the actual ground truth for "is RLS enabled on
  // this table" - rather than matching policy names against the `tenant_isolation%` naming
  // convention, which a future migration could depart from (that convention lives only in a SQL
  // comment, not anything enforced) and silently escape this check.
  private Set<String> rlsProtectedTableNames() throws Exception {
    Set<String> tables = new HashSet<>();
    try (Connection connection = adminConnection();
        Statement statement = connection.createStatement();
        ResultSet resultSet =
            statement.executeQuery(
                "SELECT c.relname FROM pg_class c "
                    + "JOIN pg_namespace n ON n.oid = c.relnamespace "
                    + "WHERE n.nspname = 'public' AND c.relkind = 'r' AND c.relrowsecurity")) {
      while (resultSet.next()) {
        tables.add(resultSet.getString("relname"));
      }
    }
    return tables;
  }

  // Scans the whole application base package, not just `.entity` - the coverage guarantee below
  // must hold regardless of which package a future entity lands in.
  private Set<String> mappedEntityTableNames() {
    ClassPathScanningCandidateComponentProvider scanner =
        new ClassPathScanningCandidateComponentProvider(false);
    scanner.addIncludeFilter(new AnnotationTypeFilter(Entity.class));

    Set<String> tables = new HashSet<>();
    scanner
        .findCandidateComponents("com.trackmywealth.backend")
        .forEach(
            beanDefinition -> {
              try {
                Class<?> entityClass = Class.forName(beanDefinition.getBeanClassName());
                Table table = entityClass.getAnnotation(Table.class);
                // An entity without an explicit @Table would fall back to Hibernate's implicit
                // naming strategy, which this suite doesn't replicate - rather than silently
                // omitting such an entity from coverage, fail loudly so the gap can't hide.
                assertThat(table != null && !table.name().isBlank())
                    .as(
                        entityClass.getName()
                            + " must declare an explicit @Table(name = ...): this suite resolves"
                            + " RLS coverage from that annotation, and silently skipping an"
                            + " entity without one would defeat the coverage check below")
                    .isTrue();
                tables.add(table.name());
              } catch (ClassNotFoundException e) {
                throw new IllegalStateException(e);
              }
            });
    return tables;
  }
}
