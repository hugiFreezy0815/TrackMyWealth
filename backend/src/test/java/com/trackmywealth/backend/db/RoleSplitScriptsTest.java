package com.trackmywealth.backend.db;

import static com.trackmywealth.backend.testsupport.RoleSplitScripts.MIGRATION_ROLE;
import static com.trackmywealth.backend.testsupport.RoleSplitScripts.RUNTIME_ROLE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.trackmywealth.backend.testsupport.RoleSplitScripts;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.output.MigrateResult;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * US-28-05 (#331): the shipped role split ({@code deploy/postgres}) on both kinds of database it
 * meets. An existing single-role installation - every migration applied by the superuser, with data
 * in it - is handed to the migration role without losing anything; a fresh database is migrated
 * from nothing by the migration role, which is no superuser. In both, the runtime role is bound by
 * row-level security and can do nothing but read and write rows. The application's HTTP path on the
 * split is {@code HardenedRuntimeAuthenticationHttpTest}.
 */
@Testcontainers
class RoleSplitScriptsTest {

  private static final String SUPERUSER = "trackmywealth";
  private static final String MIGRATION_PASSWORD = "migration-test-password";
  private static final String RUNTIME_PASSWORD = "runtime-test-password";
  private static final String FRESH = "fresh_installation";

  @Container
  static PostgreSQLContainer<?> postgres =
      new PostgreSQLContainer<>("postgres:16")
          .withDatabaseName("existing_installation")
          .withUsername(SUPERUSER)
          .withPassword(SUPERUSER);

  private static UUID workspace;
  private static MigrateResult freshMigration;

  @BeforeAll
  static void splitAnExistingAndAFreshDatabase() throws Exception {
    // An installation from before the split: the superuser ran every migration and owns it all.
    Flyway.configure().dataSource(postgres.getJdbcUrl(), SUPERUSER, SUPERUSER).load().migrate();
    workspace = UUID.randomUUID();
    try (Connection superuser = connect(existing(), SUPERUSER, SUPERUSER);
        PreparedStatement insert =
            superuser.prepareStatement("INSERT INTO workspace (id, name) VALUES (?, 'Ours')")) {
      insert.setObject(1, workspace);
      insert.executeUpdate();
    }
    split(postgres.getDatabaseName());

    split(FRESH);
    freshMigration =
        Flyway.configure()
            .dataSource(
                RoleSplitScripts.withDatabase(existing(), FRESH),
                MIGRATION_ROLE,
                MIGRATION_PASSWORD)
            .load()
            .migrate();
  }

  @Test
  void anExistingInstallationBelongsWhollyToTheMigrationRoleWithItsDataKept() throws Exception {
    assertThat(objectsNotOwnedByTheMigrationRole(existing())).isZero();
    try (Connection superuser = connect(existing(), SUPERUSER, SUPERUSER);
        PreparedStatement query =
            superuser.prepareStatement("SELECT name FROM workspace WHERE id = ?")) {
      query.setObject(1, workspace);
      try (ResultSet result = query.executeQuery()) {
        assertThat(result.next()).isTrue();
        assertThat(result.getString(1)).isEqualTo("Ours");
      }
    }
  }

  @Test
  void theMigrationRoleRunsLaterMigrationsOnAnExistingInstallation() throws Exception {
    // Nothing pending, but Flyway validates every applied migration as the new owner ...
    Flyway flyway =
        Flyway.configure().dataSource(existing(), MIGRATION_ROLE, MIGRATION_PASSWORD).load();
    assertThat(flyway.migrate().migrationsExecuted).isZero();
    // ... and as the owner it may change any table, as a later migration will.
    try (Connection migration = connect(existing(), MIGRATION_ROLE, MIGRATION_PASSWORD)) {
      migration.setAutoCommit(false);
      try (Statement statement = migration.createStatement()) {
        statement.execute("ALTER TABLE account ADD COLUMN role_split_probe INTEGER");
        statement.execute("ALTER TABLE workspace_member ENABLE ROW LEVEL SECURITY");
      } finally {
        migration.rollback();
      }
    }
  }

  @Test
  void theMigrationRoleMigratesAFreshDatabaseFromNothingWithoutBeingASuperuser() throws Exception {
    assertThat(freshMigration.success).isTrue();
    assertThat(freshMigration.migrationsExecuted).isPositive();
    assertThat(objectsNotOwnedByTheMigrationRole(fresh())).isZero();
    try (Connection superuser = connect(fresh(), SUPERUSER, SUPERUSER);
        Statement statement = superuser.createStatement();
        ResultSet role =
            statement.executeQuery(
                "SELECT rolsuper, rolbypassrls FROM pg_roles WHERE rolname = '"
                    + MIGRATION_ROLE
                    + "'")) {
      assertThat(role.next()).isTrue();
      assertThat(role.getBoolean(1)).as("superuser").isFalse();
      assertThat(role.getBoolean(2)).as("bypasses RLS, as V37 needs").isTrue();
    }
  }

  @Test
  void theRuntimeRoleIsBoundByRowLevelSecurityOnBothDatabases() throws Exception {
    for (String url : new String[] {existing(), fresh()}) {
      try (Connection runtime = connect(url, RUNTIME_ROLE, RUNTIME_PASSWORD);
          Statement statement = runtime.createStatement();
          ResultSet rows = statement.executeQuery("SELECT count(*) FROM workspace")) {
        assertThat(rows.next()).isTrue();
        assertThat(rows.getLong(1)).as("no workspace without its context, %s", url).isZero();
      }
    }
  }

  @Test
  void theRuntimeRoleMayOnlyReadAndWriteRowsAndCallTheAuthenticationLookups() throws Exception {
    for (String url : new String[] {existing(), fresh()}) {
      try (Connection runtime = connect(url, RUNTIME_ROLE, RUNTIME_PASSWORD);
          Statement statement = runtime.createStatement()) {
        try (ResultSet result =
            statement.executeQuery(
                "SELECT public.auth_workspace_member_status('" + UUID.randomUUID() + "')")) {
          assertThat(result.next()).as("V81's lookup may be called, %s", url).isTrue();
        }
        try (ResultSet result =
            statement.executeQuery("SELECT count(*) FROM flyway_schema_history")) {
          assertThat(result.next()).as("Flyway's history is readable, %s", url).isTrue();
        }
        assertDenied(statement, "CREATE TABLE public.intruder (id INTEGER)");
        assertDenied(statement, "ALTER TABLE account ADD COLUMN intruder INTEGER");
        assertDenied(statement, "TRUNCATE workspace");
        assertDenied(statement, "CREATE TEMPORARY TABLE intruder (id INTEGER)");
        assertDenied(statement, "ALTER TABLE workspace NO FORCE ROW LEVEL SECURITY");
        assertDenied(
            statement,
            "INSERT INTO flyway_schema_history (installed_rank, description, type, script,"
                + " installed_by, execution_time, success) VALUES (9999, 'x', 'SQL', 'x', 'x',"
                + " 0, TRUE)");
      }
    }
  }

  @Test
  void onlyTheTwoRolesMayConnectToEitherDatabase() throws Exception {
    try (Connection superuser = connect(existing(), SUPERUSER, SUPERUSER);
        Statement statement = superuser.createStatement()) {
      statement.execute("CREATE ROLE stranger LOGIN PASSWORD '" + RUNTIME_PASSWORD + "'");
    }
    for (String url : new String[] {existing(), fresh()}) {
      assertThatThrownBy(() -> connect(url, "stranger", RUNTIME_PASSWORD).close())
          .as(url)
          .isInstanceOf(SQLException.class)
          .hasMessageContaining("permission denied for database");
      try (Connection migration = connect(url, MIGRATION_ROLE, MIGRATION_PASSWORD)) {
        assertThat(migration.isValid(1)).as("the owner, %s", url).isTrue();
      }
    }
  }

  @Test
  void runningTheSplitAgainChangesNothing() throws Exception {
    split(postgres.getDatabaseName());
    split(FRESH);
    assertThat(objectsNotOwnedByTheMigrationRole(existing())).isZero();
    assertThat(objectsNotOwnedByTheMigrationRole(fresh())).isZero();
    try (Connection runtime = connect(existing(), RUNTIME_ROLE, RUNTIME_PASSWORD)) {
      assertThat(runtime.isValid(1)).isTrue();
    }
  }

  // Tables, views, sequences (other than a column's own), functions and types of schema public that
  // do not belong to the migration role, extension members aside.
  private static long objectsNotOwnedByTheMigrationRole(String url) throws SQLException {
    String sql =
        "SELECT"
            + " (SELECT count(*) FROM pg_class c WHERE c.relnamespace = 'public'::regnamespace"
            + "   AND c.relkind IN ('r', 'p', 'v', 'm', 'S')"
            + "   AND c.relowner <> 'trackmywealth_migration'::regrole"
            + "   AND NOT EXISTS (SELECT 1 FROM pg_depend d WHERE d.classid = 'pg_class'::regclass"
            + "     AND d.objid = c.oid AND (d.deptype = 'e'"
            + "       OR (c.relkind = 'S' AND d.deptype IN ('a', 'i')))))"
            + " + (SELECT count(*) FROM pg_proc p WHERE p.pronamespace = 'public'::regnamespace"
            + "   AND p.proowner <> 'trackmywealth_migration'::regrole"
            + "   AND NOT EXISTS (SELECT 1 FROM pg_depend d WHERE d.classid = 'pg_proc'::regclass"
            + "     AND d.objid = p.oid AND d.deptype = 'e'))"
            + " + (SELECT count(*) FROM pg_namespace n WHERE n.nspname = 'public'"
            + "   AND n.nspowner <> 'trackmywealth_migration'::regrole)";
    try (Connection superuser = connect(url, SUPERUSER, SUPERUSER);
        Statement statement = superuser.createStatement();
        ResultSet result = statement.executeQuery(sql)) {
      assertThat(result.next()).isTrue();
      return result.getLong(1);
    }
  }

  // Refused for lack of a privilege ("permission denied") or of ownership ("must be owner"), never
  // for any other reason, such as a typo in the statement.
  private static void assertDenied(Statement statement, String sql) {
    assertThatThrownBy(() -> statement.execute(sql))
        .as(sql)
        .isInstanceOf(SQLException.class)
        .message()
        .containsAnyOf("permission denied", "must be owner");
  }

  private static void split(String database) throws Exception {
    RoleSplitScripts.apply(
        postgres.getJdbcUrl(),
        SUPERUSER,
        SUPERUSER,
        database,
        MIGRATION_PASSWORD,
        RUNTIME_PASSWORD);
  }

  private static String existing() {
    return postgres.getJdbcUrl();
  }

  private static String fresh() {
    return RoleSplitScripts.withDatabase(postgres.getJdbcUrl(), FRESH);
  }

  private static Connection connect(String url, String user, String password) throws SQLException {
    return DriverManager.getConnection(url, user, password);
  }
}
