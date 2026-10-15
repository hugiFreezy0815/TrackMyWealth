package com.trackmywealth.backend.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.flyway.autoconfigure.FlywayConfigurationCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * US-28-05 (#331): the database role split (ADR 0005).
 *
 * <ul>
 *   <li>Flyway runs as the migration role when one is configured: it owns the schema, which the
 *       runtime role must not. A customizer, not {@code spring.flyway.user}: an empty {@code
 *       DB_MIGRATION_USERNAME} from a compose file would otherwise point Flyway at a user named "".
 *   <li>At startup the runtime role is checked: a superuser or {@code BYPASSRLS} role ignores every
 *       row-level security policy, so workspace isolation then rests on the application alone. That
 *       is a warning for a single-role installation that has not been split yet, and a refused
 *       start with {@code app.database.require-rls-bound-role} or a hosted topology.
 * </ul>
 */
@Configuration
public class DatabaseRoleConfig {

  private static final Logger LOG = LoggerFactory.getLogger(DatabaseRoleConfig.class);
  private static final String HOSTED_TOPOLOGY = "hosted";

  @Bean
  FlywayConfigurationCustomizer migrationRoleCustomizer(
      DatabaseRoleProperties properties, @Value("${spring.datasource.url}") String url) {
    return configuration -> {
      if (properties.hasMigrationRole()) {
        configuration.dataSource(
            url, properties.migrationUsername(), properties.migrationPassword());
      }
    };
  }

  @Bean
  ApplicationRunner runtimeRoleCheck(
      JdbcTemplate jdbc,
      DatabaseRoleProperties properties,
      @Value("${app.deployment.topology:self-hosted}") String topology) {
    boolean strict = isStrict(properties, topology);
    return args -> {
      RuntimeRole role =
          jdbc.queryForObject(
              "SELECT current_user, rolsuper, rolbypassrls FROM pg_roles"
                  + " WHERE rolname = current_user",
              (row, number) ->
                  new RuntimeRole(row.getString(1), row.getBoolean(2), row.getBoolean(3)));
      check(role, strict);
    };
  }

  /**
   * Whether a runtime role that bypasses RLS refuses the start: when configured, and always for a
   * hosted (multi-workspace) deployment, as a missing MFA key does there (NFR-DEP-002).
   */
  static boolean isStrict(DatabaseRoleProperties properties, String topology) {
    return properties.requireRlsBoundRole() || HOSTED_TOPOLOGY.equalsIgnoreCase(topology);
  }

  /**
   * Warns, or with {@code strict} throws, when {@code role} is not bound by row-level security.
   *
   * @throws IllegalStateException in strict mode, for a superuser or {@code BYPASSRLS} role
   */
  static void check(RuntimeRole role, boolean strict) {
    if (!role.bypassesRls()) {
      return;
    }
    String message =
        "The application connects to PostgreSQL as '"
            + role.name()
            + "', a role that bypasses row-level security, so workspace isolation is not enforced"
            + " by the database. Connect as an RLS-bound runtime role (see the README, \"Database"
            + " roles\").";
    if (strict) {
      throw new IllegalStateException(
          message
              + " Refused by app.database.require-rls-bound-role or the hosted deployment"
              + " topology.");
    }
    LOG.warn(message);
  }

  /** The role the application's own connections run as. */
  record RuntimeRole(String name, boolean superuser, boolean bypassRls) {

    boolean bypassesRls() {
      return superuser || bypassRls;
    }
  }
}
