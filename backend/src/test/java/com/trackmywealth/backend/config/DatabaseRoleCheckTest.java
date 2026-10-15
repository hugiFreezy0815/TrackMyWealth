package com.trackmywealth.backend.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.trackmywealth.backend.testsupport.RoleSplitScripts;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.SmartLifecycle;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * US-28-05 (#331): the runtime role check against a real PostgreSQL. A role is RLS-bound only when
 * neither it nor any role it may act as bypasses RLS or owns the schema: an owner is bound by FORCE
 * ROW LEVEL SECURITY only until it switches that off. A refused start happens before the context
 * starts its lifecycle beans - the web server and the Quartz scheduler.
 */
@Testcontainers
class DatabaseRoleCheckTest {

  private static final String PASSWORD = "role-check-password";
  private static final String OWNED_DATABASE = "owned_by_its_login";

  @Container static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

  @BeforeAll
  static void createRoles() throws SQLException {
    try (Connection superuser =
            DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        Statement statement = superuser.createStatement()) {
      statement.execute("CREATE TABLE public.probe (id INTEGER)");
      statement.execute("CREATE ROLE bound LOGIN NOBYPASSRLS PASSWORD '" + PASSWORD + "'");
      statement.execute("CREATE ROLE table_owner LOGIN PASSWORD '" + PASSWORD + "'");
      statement.execute("CREATE TABLE public.owned_probe (id INTEGER)");
      statement.execute("ALTER TABLE public.owned_probe OWNER TO table_owner");
      statement.execute(
          "CREATE ROLE owner_member LOGIN IN ROLE table_owner PASSWORD '" + PASSWORD + "'");
      statement.execute("CREATE ROLE bypasser NOLOGIN BYPASSRLS");
      statement.execute(
          "CREATE ROLE bypass_member LOGIN IN ROLE bypasser PASSWORD '" + PASSWORD + "'");
      statement.execute("CREATE ROLE database_owner LOGIN PASSWORD '" + PASSWORD + "'");
      // PostgreSQL 15+: schema public of a new database belongs to pg_database_owner.
      statement.execute("CREATE DATABASE " + OWNED_DATABASE + " OWNER database_owner");
    }
  }

  @Test
  void aRoleWithOnlyRowPrivilegesIsRlsBound() {
    assertThatCode(() -> strictCheck(postgres.getJdbcUrl(), "bound", PASSWORD))
        .doesNotThrowAnyException();
  }

  @ParameterizedTest
  @CsvSource({
    "table_owner, may switch RLS off",
    "owner_member, may switch RLS off",
    "bypass_member, superuser or BYPASSRLS",
  })
  void aRoleThatOwnsATableOrMayActAsOneThatBypassesRlsIsRefused(String role, String reason) {
    assertThatThrownBy(() -> strictCheck(postgres.getJdbcUrl(), role, PASSWORD))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("'" + role + "'")
        .hasMessageContaining(reason);
  }

  @Test
  void theDatabaseOwnerIsRefusedThroughPgDatabaseOwner() {
    String url = RoleSplitScripts.withDatabase(postgres.getJdbcUrl(), OWNED_DATABASE);
    assertThatThrownBy(() -> strictCheck(url, "database_owner", PASSWORD))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("may switch RLS off");
  }

  @Test
  void theSuperuserIsRefusedForBothReasons() {
    assertThatThrownBy(
            () ->
                strictCheck(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword()))
        .hasMessageContaining("superuser or BYPASSRLS")
        .hasMessageContaining("may switch RLS off");
  }

  @Test
  void aRefusedStartNeverStartsTheLifecycleBeans() {
    LifecycleProbe probe = new LifecycleProbe();

    contextRunner(true, probe)
        .run(
            context -> {
              assertThat(context).hasFailed();
              assertThat(context.getStartupFailure())
                  .isInstanceOf(IllegalStateException.class)
                  .hasMessageContaining("superuser or BYPASSRLS");
            });

    assertThat(probe.started).as("web server and scheduler stand-in").isFalse();
  }

  @Test
  void aWarnedStartStartsTheLifecycleBeansAsUsual() {
    LifecycleProbe probe = new LifecycleProbe();

    contextRunner(false, probe).run(context -> assertThat(context).hasNotFailed());

    assertThat(probe.started).as("the probe would have seen a start").isTrue();
  }

  // As the superuser, which the check refuses in strict mode and only warns about otherwise.
  private static ApplicationContextRunner contextRunner(boolean strict, LifecycleProbe probe) {
    DriverManagerDataSource dataSource =
        new DriverManagerDataSource(
            postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    return new ApplicationContextRunner()
        .withUserConfiguration(DatabaseRoleConfig.class)
        .withPropertyValues("spring.datasource.url=" + postgres.getJdbcUrl())
        .withBean(
            DatabaseRoleProperties.class, () -> new DatabaseRoleProperties(null, null, strict))
        .withBean(JdbcTemplate.class, () -> new JdbcTemplate(dataSource))
        .withBean(LifecycleProbe.class, () -> probe);
  }

  private static void strictCheck(String url, String user, String password) {
    new DatabaseRoleConfig()
        .runtimeRoleCheck(
            new JdbcTemplate(new DriverManagerDataSource(url, user, password)),
            new DatabaseRoleProperties(null, null, true),
            "self-hosted")
        .afterSingletonsInstantiated();
  }

  /** Stands in for the web server and the Quartz scheduler, both started as lifecycle beans. */
  static final class LifecycleProbe implements SmartLifecycle {

    private volatile boolean started;
    private volatile boolean running;

    @Override
    public void start() {
      started = true;
      running = true;
    }

    @Override
    public void stop() {
      running = false;
    }

    @Override
    public boolean isRunning() {
      return running;
    }
  }
}
