package com.trackmywealth.backend.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.trackmywealth.backend.config.DatabaseRoleConfig.RuntimeRole;
import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.flywaydb.core.internal.jdbc.DriverDataSource;
import org.junit.jupiter.api.Test;

/**
 * US-28-05 (#331): Flyway's role switch and the runtime role check, without a database. With a real
 * one: {@code HardenedRuntimeAuthenticationHttpTest} (the split, strict) and every other Spring
 * test (a superuser, which only warns).
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

    DriverDataSource dataSource = (DriverDataSource) flyway.getDataSource();
    assertThat(dataSource.getUrl()).isEqualTo(URL);
    assertThat(dataSource.getUser()).isEqualTo("trackmywealth_migration");
    assertThat(dataSource.getPassword()).isEqualTo("secret");
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
                    new RuntimeRole("trackmywealth_runtime", false, false), true))
        .doesNotThrowAnyException();
  }

  @Test
  void aRoleThatBypassesRlsIsOnlyAWarningByDefault() {
    assertThatCode(
            () -> DatabaseRoleConfig.check(new RuntimeRole("trackmywealth", true, false), false))
        .doesNotThrowAnyException();
  }

  @Test
  void strictModeRefusesASuperuserAndABypassRlsRole() {
    for (RuntimeRole role :
        new RuntimeRole[] {
          new RuntimeRole("postgres", true, false), new RuntimeRole("bypassing", false, true)
        }) {
      assertThatThrownBy(() -> DatabaseRoleConfig.check(role, true))
          .as(role.name())
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("'" + role.name() + "'")
          .hasMessageContaining("app.database.require-rls-bound-role");
    }
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
