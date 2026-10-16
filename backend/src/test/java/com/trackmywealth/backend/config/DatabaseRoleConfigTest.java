package com.trackmywealth.backend.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.trackmywealth.backend.config.DatabaseRoleConfig.RuntimeRole;
import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.junit.jupiter.api.Test;

/**
 * US-28-05 (#331): Flyway's role switch and the runtime role check, without a database. With a real
 * one: {@code DatabaseRoleCheckTest} (the check's query and when it runs), {@code
 * HardenedRuntimeAuthenticationHttpTest} (the split, strict) and every other Spring test (a
 * superuser, which only warns).
 */
class DatabaseRoleConfigTest {

  private static final String URL = "jdbc:postgresql://db.invalid:5432/trackmywealth";
  private final DatabaseRoleConfig config = new DatabaseRoleConfig();

  @Test
  void flywayRunsAsTheMigrationRoleOnTheApplicationsDatabase() {
    FluentConfiguration flyway = new FluentConfiguration();

    config
        .migrationRoleCustomizer(
            new DatabaseRoleProperties("trackmywealth_migration", "secret", false), URL)
        .customize(flyway);

    assertThat(flyway.getUrl()).isEqualTo(URL);
    assertThat(flyway.getUser()).isEqualTo("trackmywealth_migration");
    assertThat(flyway.getPassword()).isEqualTo("secret");
  }

  @Test
  void withoutAMigrationRoleFlywayKeepsTheApplicationsDataSource() {
    for (String unset : new String[] {null, "", "  "}) {
      FluentConfiguration flyway = new FluentConfiguration();

      config
          .migrationRoleCustomizer(new DatabaseRoleProperties(unset, unset, false), URL)
          .customize(flyway);

      assertThat(flyway.getDataSource()).as("migration user %s", unset).isNull();
    }
  }

  @Test
  void anRlsBoundRuntimeRolePassesEvenInStrictMode() {
    assertThatCode(
            () ->
                DatabaseRoleConfig.check(
                    new RuntimeRole("trackmywealth_runtime", false, false, false), true))
        .doesNotThrowAnyException();
  }

  @Test
  void aRoleThatIsNotRlsBoundIsOnlyAWarningByDefault() {
    for (RuntimeRole role :
        new RuntimeRole[] {
          new RuntimeRole("trackmywealth", true, true, true),
          new RuntimeRole("owner", false, true, false)
        }) {
      assertThatCode(() -> DatabaseRoleConfig.check(role, false))
          .as(role.name())
          .doesNotThrowAnyException();
    }
  }

  @Test
  void strictModeRefusesARoleThatBypassesRls() {
    assertThatThrownBy(
            () -> DatabaseRoleConfig.check(new RuntimeRole("bypassing", true, false, false), true))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("'bypassing'")
        .hasMessageContaining("superuser or BYPASSRLS")
        .hasMessageNotContaining("owns")
        .hasMessageContaining("app.database.require-rls-bound-role");
  }

  @Test
  void strictModeRefusesTheSchemaOwnerThoughForceRlsBindsIt() {
    // FORCE ROW LEVEL SECURITY binds the owner too - until the owner switches it off.
    assertThatThrownBy(
            () -> DatabaseRoleConfig.check(new RuntimeRole("owner", false, true, false), true))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("'owner'")
        .hasMessageContaining("may switch RLS off")
        .hasMessageNotContaining("BYPASSRLS role");
  }

  @Test
  void strictModeRefusesARoleThatMayTruncateATable() {
    // TRUNCATE empties a table without consulting a single policy.
    assertThatThrownBy(
            () -> DatabaseRoleConfig.check(new RuntimeRole("truncater", false, false, true), true))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("'truncater'")
        .hasMessageContaining("may TRUNCATE a table")
        .hasMessageNotContaining("BYPASSRLS role")
        .hasMessageNotContaining("switch RLS off");
  }

  @Test
  void strictModeNamesEveryReasonForASuperuser() {
    assertThatThrownBy(
            () -> DatabaseRoleConfig.check(new RuntimeRole("postgres", true, true, true), true))
        .hasMessageContaining("superuser or BYPASSRLS")
        .hasMessageContaining("may switch RLS off")
        .hasMessageContaining("may TRUNCATE a table");
  }

  @Test
  void aHostedDeploymentIsAlwaysStrict() {
    DatabaseRoleProperties lenient = new DatabaseRoleProperties(null, null, false);
    assertThat(DatabaseRoleConfig.isStrict(lenient, "self-hosted")).isFalse();
    assertThat(DatabaseRoleConfig.isStrict(lenient, "hosted")).isTrue();
    assertThat(
            DatabaseRoleConfig.isStrict(
                new DatabaseRoleProperties(null, null, true), "self-hosted"))
        .isTrue();
  }

  @Test
  void thePropertiesNeverPrintThePassword() {
    assertThat(new DatabaseRoleProperties("trackmywealth_migration", "hunter2", true).toString())
        .contains("trackmywealth_migration")
        .doesNotContain("hunter2");
  }
}
