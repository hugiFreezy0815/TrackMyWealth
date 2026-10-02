package com.trackmywealth.backend.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.trackmywealth.backend.security.SystemWorkspaceContext;
import com.trackmywealth.backend.security.WorkspacePrincipal;
import com.zaxxer.hikari.HikariDataSource;
import jakarta.persistence.EntityManager;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * US-28-01's Definition of Done: the pooled-connection-leakage scenario (a test pool sized to 1,
 * two sequential transactions, assert no leakage), plus the story's explicit "no resolvable
 * workspace context" edge case actually causing every RLS policy to deny by default - not just that
 * the session variable happens to be unset, but that a real, RLS-restricted database role sees
 * nothing without it. That second half can't be demonstrated through the application's own
 * datasource today: this project's runtime role is still the migration-owning superuser (see
 * database-schema.md, "Production hardening not yet wired up" - a separately tracked gap, not this
 * story's job to close), and Postgres superusers bypass row-level security unconditionally
 * regardless of FORCE ROW LEVEL SECURITY. So the RLS half of this test builds its own NOSUPERUSER
 * role, scoped to this test only, matching the role V20's own commented-out production template
 * describes - proving the policy mechanism itself is sound independent of that pending
 * infrastructure change.
 */
@Testcontainers
@SpringBootTest
class WorkspaceContextTransactionExecutionListenerTest {

  @Container
  static PostgreSQLContainer<?> postgres =
      new PostgreSQLContainer<>("postgres:16")
          .withDatabaseName("trackmywealth")
          .withUsername("trackmywealth")
          .withPassword("trackmywealth");

  @DynamicPropertySource
  static void datasourceProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", postgres::getJdbcUrl);
    registry.add("spring.datasource.username", postgres::getUsername);
    registry.add("spring.datasource.password", postgres::getPassword);
  }

  @Autowired PlatformTransactionManager transactionManager;
  @Autowired EntityManager entityManager;
  @Autowired DataSource dataSource;

  @AfterEach
  void clearSecurityContext() {
    SecurityContextHolder.clearContext();
  }

  @Test
  void noAuthenticationLeavesWorkspaceContextUnset() {
    TransactionTemplate tx = new TransactionTemplate(transactionManager);
    String setting = tx.execute(status -> currentWorkspaceIdSetting());
    // A pooled connection that has never had app.current_workspace_id touched reports true NULL;
    // one where a prior transaction set it (then reverted at commit, per is_local=true semantics)
    // reports '' rather than NULL - a Postgres quirk of custom (extension-namespaced) GUCs, not a
    // leak: either way, no other workspace's real id is observable. isNullOrEmpty() asserts the
    // actual security property (nothing leaked through) rather than one specific representation.
    assertThat(setting).isNullOrEmpty();
  }

  @Test
  void workspaceContextIsSetForAnAuthenticatedPrincipal() {
    UUID workspaceId = UUID.randomUUID();
    authenticateAs(workspaceId);

    TransactionTemplate tx = new TransactionTemplate(transactionManager);
    String setting = tx.execute(status -> currentWorkspaceIdSetting());

    assertThat(setting).isEqualTo(workspaceId.toString());
  }

  // #223: a background job re-running transfer detection has no request principal; the system
  // context sets the workspace for the transactions it starts and restores the thread afterwards.
  @Test
  void systemWorkProvidesItsWorkspaceToRowLevelSecurityAndRestoresTheThread() {
    UUID callersWorkspace = UUID.randomUUID();
    authenticateAs(callersWorkspace);
    UUID jobsWorkspace = UUID.randomUUID();
    TransactionTemplate tx = new TransactionTemplate(transactionManager);
    List<String> seen = new ArrayList<>();

    SystemWorkspaceContext.runInWorkspace(
        jobsWorkspace, () -> seen.add(tx.execute(status -> currentWorkspaceIdSetting())));

    assertThat(seen).containsExactly(jobsWorkspace.toString());
    String afterwards = tx.execute(status -> currentWorkspaceIdSetting());
    assertThat(afterwards).isEqualTo(callersWorkspace.toString());
  }

  @Test
  void workspaceContextDoesNotLeakAcrossTwoTransactionsSharingOnePooledConnection() {
    // Forcing the pool to size 1 at the datasource-bean level (e.g. via a @DynamicPropertySource
    // override) causes the full application context to fail to start: Quartz's JobStore, Flyway,
    // and JPA's own JDBC metadata inspection all need a connection close together during startup
    // and end up contending over the single slot. Resizing live via Hikari's own runtime-adjustable
    // pool (HikariConfigMXBean), scoped to just this already-started test, gets the same DoD
    // guarantee - the two transactions below are physically forced onto the same connection -
    // without touching how the application boots.
    HikariDataSource hikariDataSource = (HikariDataSource) dataSource;
    int originalMaximumPoolSize = hikariDataSource.getMaximumPoolSize();
    hikariDataSource.setMaximumPoolSize(1);
    try {
      UUID workspaceA = UUID.randomUUID();
      UUID workspaceB = UUID.randomUUID();
      TransactionTemplate tx = new TransactionTemplate(transactionManager);

      authenticateAs(workspaceA);
      String firstSetting = tx.execute(status -> currentWorkspaceIdSetting());
      assertThat(firstSetting).isEqualTo(workspaceA.toString());

      // A completed transaction's is_local=true setting must not survive into the next one, even
      // though (pool size 1, forced above) this is physically the same JDBC connection.
      authenticateAs(workspaceB);
      String secondSetting = tx.execute(status -> currentWorkspaceIdSetting());
      assertThat(secondSetting).isEqualTo(workspaceB.toString()).isNotEqualTo(firstSetting);

      // And the same for falling back to no context at all on that same reused connection - see
      // noAuthenticationLeavesWorkspaceContextUnset for why isNullOrEmpty(), not isNull().
      SecurityContextHolder.clearContext();
      String thirdSetting = tx.execute(status -> currentWorkspaceIdSetting());
      assertThat(thirdSetting).isNotEqualTo(secondSetting);
      assertThat(thirdSetting).isNullOrEmpty();
    } finally {
      hikariDataSource.setMaximumPoolSize(originalMaximumPoolSize);
    }
  }

  @Test
  void noWorkspaceContextMeansRowLevelSecurityDeniesByDefault() throws Exception {
    UUID otherWorkspaceId;
    UUID ownWorkspaceId;
    try (Connection superuser = superuserConnection();
        Statement statement = superuser.createStatement()) {
      createRestrictedRuntimeRole(statement);
      otherWorkspaceId = insertWorkspace(statement, "Other Workspace");
      ownWorkspaceId = insertWorkspace(statement, "Own Workspace");
    }

    try (Connection restricted = restrictedRoleConnection()) {
      // No app.current_workspace_id set at all: current_workspace_id() is NULL, so
      // "workspace_id = current_workspace_id()" is NULL (falsy) for every row - RLS denies by
      // default, per FR-TEN-001..003, rather than requiring an explicit deny rule anywhere.
      assertThat(visibleWorkspaceIds(restricted)).isEmpty();

      // Confirms that's RLS actually filtering (not e.g. the role lacking SELECT entirely, which
      // would also show zero rows for the wrong reason): setting the context reveals exactly the
      // workspace in context, never the other one that also exists in the same table.
      setWorkspaceContext(restricted, ownWorkspaceId);
      assertThat(visibleWorkspaceIds(restricted)).containsExactly(ownWorkspaceId);

      setWorkspaceContext(restricted, otherWorkspaceId);
      assertThat(visibleWorkspaceIds(restricted)).containsExactly(otherWorkspaceId);
    }
  }

  private String currentWorkspaceIdSetting() {
    return (String)
        entityManager
            .createNativeQuery("SELECT current_setting('app.current_workspace_id', true)")
            .getSingleResult();
  }

  private void authenticateAs(UUID workspaceId) {
    WorkspacePrincipal principal = () -> workspaceId;
    var authentication = new TestingAuthenticationToken(principal, null, "ROLE_STANDARD_USER");
    authentication.setAuthenticated(true);
    SecurityContextHolder.getContext().setAuthentication(authentication);
  }

  private Connection superuserConnection() throws Exception {
    return DriverManager.getConnection(
        postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
  }

  private void createRestrictedRuntimeRole(Statement statement) throws Exception {
    // Mirrors the production role split V20's own commented-out template describes: a login role
    // with ordinary DML rights and no superuser/bypass-RLS privilege of any kind.
    //
    // DROP OWNED BY revokes every privilege previously GRANTed to this role (and would drop any
    // object it owns, though it owns none here) before the role itself is dropped - without it, a
    // local re-run of just this test method against an already-running Testcontainers instance
    // (container never restarted) fails on DROP ROLE with "cannot be dropped because some objects
    // depend on it", since the GRANT below leaves dependent privileges behind otherwise. DROP
    // OWNED BY has no IF EXISTS form and errors if the role is missing, so it's only run when a
    // prior test run actually left the role behind (never true on a fresh container/first run).
    try (var roleExists =
        statement.executeQuery("SELECT 1 FROM pg_roles WHERE rolname = 'tenancy_test_runtime'")) {
      if (roleExists.next()) {
        statement.execute("DROP OWNED BY tenancy_test_runtime");
      }
    }
    statement.execute("DROP ROLE IF EXISTS tenancy_test_runtime");
    statement.execute(
        "CREATE ROLE tenancy_test_runtime LOGIN PASSWORD 'tenancy_test_runtime' NOSUPERUSER");
    statement.execute(
        "GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO tenancy_test_runtime");
  }

  private UUID insertWorkspace(Statement statement, String name) throws Exception {
    UUID id = UUID.randomUUID();
    statement.execute("INSERT INTO workspace (id, name) VALUES ('%s', '%s')".formatted(id, name));
    return id;
  }

  private Connection restrictedRoleConnection() throws Exception {
    return DriverManager.getConnection(
        postgres.getJdbcUrl(), "tenancy_test_runtime", "tenancy_test_runtime");
  }

  private List<UUID> visibleWorkspaceIds(Connection connection) throws Exception {
    var ids = new ArrayList<UUID>();
    try (Statement statement = connection.createStatement();
        var resultSet = statement.executeQuery("SELECT id FROM workspace")) {
      while (resultSet.next()) {
        ids.add(UUID.fromString(resultSet.getString(1)));
      }
    }
    return ids;
  }

  private void setWorkspaceContext(Connection connection, UUID workspaceId) throws Exception {
    try (var preparedStatement =
        connection.prepareStatement("SELECT set_config('app.current_workspace_id', ?, false)")) {
      preparedStatement.setString(1, workspaceId.toString());
      preparedStatement.execute();
    }
  }
}
