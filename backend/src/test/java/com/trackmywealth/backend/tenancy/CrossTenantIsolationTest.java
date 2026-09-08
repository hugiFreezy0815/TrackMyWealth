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
      Set.of("workspace", "workspace_member", "financial_institution");

  private UUID workspaceAId;
  private UUID workspaceBId;
  private UUID memberAId;
  private UUID memberBId;
  private UUID institutionAId;
  private UUID institutionBId;

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
          "TRUNCATE TABLE financial_institution, workspace_member, workspace RESTART IDENTITY"
              + " CASCADE");
    }

    try (Connection connection = testRoleConnection()) {
      connection.setAutoCommit(false);

      workspaceAId = UUID.randomUUID();
      setWorkspaceContext(connection, workspaceAId);
      insertWorkspace(connection, workspaceAId, "Cross-Tenant Test Workspace A");
      memberAId = insertMember(connection, workspaceAId, "Member A");
      institutionAId = personalAssetsId(connection, workspaceAId);

      workspaceBId = UUID.randomUUID();
      setWorkspaceContext(connection, workspaceBId);
      insertWorkspace(connection, workspaceBId, "Cross-Tenant Test Workspace B");
      memberBId = insertMember(connection, workspaceBId, "Member B");
      institutionBId = personalAssetsId(connection, workspaceBId);

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

  // A cross-tenant suite that only ever asserts "denied" can pass vacuously if RLS is
  // accidentally denying everyone, including a workspace reading its own data - this is the
  // false-negative guard against that.
  @Test
  void eachWorkspaceCanStillSeeItsOwnData() throws Exception {
    assertThat(rowVisibleUnderContext(workspaceAId, "workspace", workspaceAId)).isTrue();
    assertThat(rowVisibleUnderContext(workspaceAId, "workspace_member", memberAId)).isTrue();
    assertThat(rowVisibleUnderContext(workspaceAId, "financial_institution", institutionAId))
        .isTrue();
    assertThat(rowVisibleUnderContext(workspaceBId, "workspace", workspaceBId)).isTrue();
    assertThat(rowVisibleUnderContext(workspaceBId, "workspace_member", memberBId)).isTrue();
    assertThat(rowVisibleUnderContext(workspaceBId, "financial_institution", institutionBId))
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

  private Set<String> rlsProtectedTableNames() throws Exception {
    Set<String> tables = new HashSet<>();
    try (Connection connection = adminConnection();
        Statement statement = connection.createStatement();
        ResultSet resultSet =
            statement.executeQuery(
                "SELECT DISTINCT tablename FROM pg_policies "
                    + "WHERE schemaname = 'public' AND policyname LIKE 'tenant_isolation%'")) {
      while (resultSet.next()) {
        tables.add(resultSet.getString("tablename"));
      }
    }
    return tables;
  }

  private Set<String> mappedEntityTableNames() {
    ClassPathScanningCandidateComponentProvider scanner =
        new ClassPathScanningCandidateComponentProvider(false);
    scanner.addIncludeFilter(new AnnotationTypeFilter(Entity.class));

    Set<String> tables = new HashSet<>();
    scanner
        .findCandidateComponents("com.trackmywealth.backend.entity")
        .forEach(
            beanDefinition -> {
              try {
                Class<?> entityClass = Class.forName(beanDefinition.getBeanClassName());
                Table table = entityClass.getAnnotation(Table.class);
                if (table != null && !table.name().isBlank()) {
                  tables.add(table.name());
                }
              } catch (ClassNotFoundException e) {
                throw new IllegalStateException(e);
              }
            });
    return tables;
  }
}
