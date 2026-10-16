package com.trackmywealth.backend.db;

import static com.trackmywealth.backend.testsupport.RoleSplitScripts.MIGRATION_ROLE;
import static com.trackmywealth.backend.testsupport.RoleSplitScripts.RUNTIME_ROLE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.output.MigrateResult;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.testcontainers.containers.Container.ExecResult;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.output.ToStringConsumer;
import org.testcontainers.containers.startupcheck.OneShotStartupCheckStrategy;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.MountableFile;

/**
 * US-28-05 (#331): the shell layer of the role split, as docker compose runs it - the postgres
 * image's init hook on a fresh volume, and {@code role-split.sh} run inside the container for an
 * existing one. {@code RoleSplitScriptsTest} covers the SQL over JDBC; this covers what JDBC cannot
 * reach: the passwords read with {@code \getenv}, the script's refusals, and the hook itself. The
 * fresh stack is then migrated by the migration role, as the backend's first start would.
 */
@Testcontainers
class RoleSplitShellTest {

  private static final String SUPERUSER = "trackmywealth";
  private static final String DATABASE = "trackmywealth";
  private static final String MIGRATION_PASSWORD = "shell-migration-password";
  private static final String RUNTIME_PASSWORD = "shell-runtime-password";
  private static final String SCRIPT = "/trackmywealth/role-split.sh";
  private static final Path DEPLOY = Path.of("..", "deploy", "postgres");

  // A fresh compose volume with DB_MIGRATION_PASSWORD set: the hook splits on first start.
  @Container
  static PostgreSQLContainer<?> split =
      withRoleSplitScripts(newPostgres())
          .withEnv("TMW_MIGRATION_PASSWORD", MIGRATION_PASSWORD)
          .withEnv("TMW_RUNTIME_PASSWORD", RUNTIME_PASSWORD);

  // A fresh compose volume without it: the single-role setup, as before the split.
  @Container static PostgreSQLContainer<?> singleRole = withRoleSplitScripts(newPostgres());

  private static MigrateResult migration;

  @BeforeAll
  static void migrateTheFreshStackAsTheMigrationRole() {
    migration =
        Flyway.configure()
            .dataSource(split.getJdbcUrl(), MIGRATION_ROLE, MIGRATION_PASSWORD)
            .load()
            .migrate();
  }

  @Test
  void theInitHookCreatesBothRolesAndHandsThemTheDatabase() throws Exception {
    try (Connection superuser = superuser(split);
        Statement statement = superuser.createStatement();
        ResultSet roles =
            statement.executeQuery(
                "SELECT rolname, rolcanlogin, rolsuper, rolbypassrls, rolcreatedb, rolcreaterole"
                    + " FROM pg_roles WHERE rolname LIKE 'trackmywealth\\_%' ORDER BY rolname")) {
      List<String> found = new ArrayList<>();
      while (roles.next()) {
        found.add(
            "%s login=%s super=%s bypassrls=%s createdb=%s createrole=%s"
                .formatted(
                    roles.getString(1),
                    roles.getBoolean(2),
                    roles.getBoolean(3),
                    roles.getBoolean(4),
                    roles.getBoolean(5),
                    roles.getBoolean(6)));
      }
      assertThat(found)
          .containsExactly(
              MIGRATION_ROLE
                  + " login=true super=false bypassrls=true createdb=false createrole=false",
              RUNTIME_ROLE
                  + " login=true super=false bypassrls=false createdb=false createrole=false");
    }
    assertThat(databaseOwner(split)).isEqualTo(MIGRATION_ROLE);
  }

  @Test
  void thePasswordsFromTheEnvironmentLogInOverTheNetworkAndNothingElseDoes() throws Exception {
    try (Connection migrationRole = connect(split, MIGRATION_ROLE, MIGRATION_PASSWORD);
        Connection runtime = connect(split, RUNTIME_ROLE, RUNTIME_PASSWORD)) {
      assertThat(migrationRole.isValid(1)).isTrue();
      assertThat(runtime.isValid(1)).isTrue();
    }
    assertThatThrownBy(() -> connect(split, RUNTIME_ROLE, MIGRATION_PASSWORD).close())
        .isInstanceOf(SQLException.class)
        .hasMessageContaining("password authentication failed");
  }

  @Test
  void theMigrationRoleMigratesTheFreshStackAndTheRuntimeRoleIsBoundByRls() throws Exception {
    assertThat(migration.success).isTrue();
    assertThat(migration.migrationsExecuted).isPositive();
    try (Connection superuser = superuser(split);
        Statement statement = superuser.createStatement();
        ResultSet installers =
            statement.executeQuery(
                "SELECT DISTINCT installed_by FROM flyway_schema_history WHERE version IS NOT"
                    + " NULL")) {
      List<String> installedBy = new ArrayList<>();
      while (installers.next()) {
        installedBy.add(installers.getString(1));
      }
      assertThat(installedBy).as("every migration ran as").containsExactly(MIGRATION_ROLE);
    }
    try (Connection runtime = connect(split, RUNTIME_ROLE, RUNTIME_PASSWORD);
        Statement statement = runtime.createStatement();
        ResultSet rows =
            statement.executeQuery(
                "SELECT (SELECT count(*) FROM workspace), rolsuper OR rolbypassrls"
                    + " FROM pg_roles WHERE rolname = current_user")) {
      assertThat(rows.next()).isTrue();
      assertThat(rows.getLong(1)).as("no workspace without its context").isZero();
      assertThat(rows.getBoolean(2)).as("bypasses RLS").isFalse();
    }
  }

  @ParameterizedTest
  @CsvSource({
    "CHANGE-ME, " + RUNTIME_PASSWORD + ", at least 16 characters",
    "please-change-me-later, " + RUNTIME_PASSWORD + ", at least 16 characters",
    "too-short, " + RUNTIME_PASSWORD + ", at least 16 characters",
    MIGRATION_PASSWORD + ", " + MIGRATION_PASSWORD + ", need different passwords",
  })
  void theScriptRefusesWeakOrSharedPasswordsAndChangesNothing(
      String migrationPassword, String runtimePassword, String reason) throws Exception {
    ExecResult result =
        split.execInContainer(
            "env",
            "TMW_MIGRATION_PASSWORD=" + migrationPassword,
            "TMW_RUNTIME_PASSWORD=" + runtimePassword,
            "bash",
            SCRIPT);

    assertThat(result.getExitCode()).isEqualTo(1);
    assertThat(result.getStderr()).contains(reason);
    try (Connection runtime = connect(split, RUNTIME_ROLE, RUNTIME_PASSWORD)) {
      assertThat(runtime.isValid(1)).as("the old password still works").isTrue();
    }
  }

  @Test
  void theScriptRefusesADatabaseNameItWouldHaveToQuote() throws Exception {
    ExecResult result =
        split.execInContainer(
            "env", "TMW_DATABASE=x\"; DROP DATABASE postgres; --", "bash", SCRIPT);

    assertThat(result.getExitCode()).isEqualTo(1);
    assertThat(result.getStderr()).contains("plain lower-case identifier");
  }

  @Test
  void runningTheScriptAgainOnTheSplitDatabaseSucceedsAndKeepsIt() throws Exception {
    // The existing installation's path: the database exists, so it is handed over, not created.
    ExecResult result = split.execInContainer("bash", SCRIPT);

    assertThat(result.getExitCode()).as(result.getStderr()).isZero();
    assertThat(result.getStdout()).contains("now belongs to " + MIGRATION_ROLE);
    assertThat(databaseOwner(split)).isEqualTo(MIGRATION_ROLE);
    try (Connection runtime = connect(split, RUNTIME_ROLE, RUNTIME_PASSWORD)) {
      assertThat(runtime.isValid(1)).isTrue();
    }
  }

  @Test
  void withoutAMigrationPasswordTheHookLeavesASingleRoleSetup() throws Exception {
    assertThat(singleRole.getLogs()).contains("single-role setup, no roles created");
    try (Connection superuser = superuser(singleRole);
        Statement statement = superuser.createStatement();
        ResultSet roles =
            statement.executeQuery(
                "SELECT count(*) FROM pg_roles WHERE rolname LIKE 'trackmywealth\\_%'")) {
      assertThat(roles.next()).isTrue();
      assertThat(roles.getLong(1)).isZero();
    }
    assertThat(databaseOwner(singleRole)).isEqualTo(SUPERUSER);
  }

  @Test
  void aFreshVolumeWithThePlaceholderPasswordRefusesToFinishItsFirstStart() {
    // The postgres entrypoint stops at the hook's failure; compose shows it in the postgres log.
    ToStringConsumer log = new ToStringConsumer();
    try (GenericContainer<?> placeholder =
        withRoleSplitScripts(new GenericContainer<>("postgres:16"))
            .withEnv("POSTGRES_USER", SUPERUSER)
            .withEnv("POSTGRES_PASSWORD", SUPERUSER)
            .withEnv("TMW_MIGRATION_PASSWORD", "CHANGE-ME")
            .withEnv("TMW_RUNTIME_PASSWORD", RUNTIME_PASSWORD)
            .withLogConsumer(log)
            .withStartupCheckStrategy(
                new OneShotStartupCheckStrategy().withTimeout(Duration.ofSeconds(60)))) {
      assertThatThrownBy(placeholder::start)
          .as("the container exits instead of serving")
          .isInstanceOf(RuntimeException.class);
    }
    // ... and for that reason, not a timeout of a server that came up.
    assertThat(log.toUtf8String())
        .contains("TMW_MIGRATION_PASSWORD must be a real password of at least 16 characters")
        .doesNotContain("PostgreSQL init process complete");
  }

  private static PostgreSQLContainer<?> newPostgres() {
    return new PostgreSQLContainer<>("postgres:16")
        .withDatabaseName(DATABASE)
        .withUsername(SUPERUSER)
        .withPassword(SUPERUSER);
  }

  // The same files at the same paths as docker-compose.yml's two mounts.
  private static <T extends GenericContainer<?>> T withRoleSplitScripts(T container) {
    for (String file :
        new String[] {"role-split.sh", "role-split-cluster.sql", "role-split-database.sql"}) {
      container.withCopyFileToContainer(
          MountableFile.forHostPath(DEPLOY.resolve(file), 0644), "/trackmywealth/" + file);
    }
    container.withCopyFileToContainer(
        MountableFile.forHostPath(DEPLOY.resolve("initdb/10-trackmywealth-roles.sh"), 0755),
        "/docker-entrypoint-initdb.d/10-trackmywealth-roles.sh");
    return container;
  }

  private static String databaseOwner(PostgreSQLContainer<?> postgres) throws SQLException {
    try (Connection superuser = superuser(postgres);
        Statement statement = superuser.createStatement();
        ResultSet owner =
            statement.executeQuery(
                "SELECT pg_get_userbyid(datdba) FROM pg_database WHERE datname = '"
                    + DATABASE
                    + "'")) {
      assertThat(owner.next()).isTrue();
      return owner.getString(1);
    }
  }

  private static Connection superuser(PostgreSQLContainer<?> postgres) throws SQLException {
    return connect(postgres, SUPERUSER, SUPERUSER);
  }

  private static Connection connect(PostgreSQLContainer<?> postgres, String user, String password)
      throws SQLException {
    return DriverManager.getConnection(postgres.getJdbcUrl(), user, password);
  }
}
