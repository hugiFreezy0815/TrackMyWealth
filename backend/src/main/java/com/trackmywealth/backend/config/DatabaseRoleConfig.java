package com.trackmywealth.backend.config;

import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.annotation.Value;
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
 *   <li>At startup the runtime role is checked. It is not bound by row-level security when it, or a
 *       role it may act as, is a superuser or {@code BYPASSRLS}, which ignore every policy, or owns
 *       the schema or its tables, which may switch the policies off. Workspace isolation then rests
 *       on the application alone. That is a warning for a single-role installation that has not
 *       been split yet, and a refused start with {@code app.database.require-rls-bound-role} or a
 *       hosted topology.
 * </ul>
 *
 * <p>The check runs once every singleton exists - after Flyway - but before the context starts its
 * lifecycle beans, so a refused start never serves a request or fires a Quartz job.
 */
@Configuration
public class DatabaseRoleConfig {

  private static final Logger LOG = LoggerFactory.getLogger(DatabaseRoleConfig.class);
  private static final String HOSTED_TOPOLOGY = "hosted";

  // pg_has_role(..., 'MEMBER') is true for the role itself and every role it can SET ROLE to;
  // a superuser is a member of every role. In PostgreSQL 15+ schema public may belong to
  // pg_database_owner, whose only member is the database's owner.
  private static final String RUNTIME_ROLE_QUERY =
      """
      SELECT current_user,
          EXISTS (
              SELECT 1 FROM pg_roles AS r
              WHERE (r.rolsuper OR r.rolbypassrls)
                  AND pg_has_role(current_user, r.oid, 'MEMBER')
          ),
          EXISTS (
              SELECT 1 FROM pg_namespace AS n
              WHERE n.nspname = 'public' AND pg_has_role(current_user, n.nspowner, 'MEMBER')
          ) OR EXISTS (
              SELECT 1 FROM pg_class AS c
              WHERE c.relnamespace = 'public'::REGNAMESPACE
                  AND c.relkind IN ('r', 'p')
                  AND pg_has_role(current_user, c.relowner, 'MEMBER')
          )
      """;

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
  SmartInitializingSingleton runtimeRoleCheck(
      JdbcTemplate jdbc,
      DatabaseRoleProperties properties,
      @Value("${app.deployment.topology:self-hosted}") String topology) {
    boolean strict = isStrict(properties, topology);
    return () ->
        check(
            jdbc.queryForObject(
                RUNTIME_ROLE_QUERY,
                (row, number) ->
                    new RuntimeRole(row.getString(1), row.getBoolean(2), row.getBoolean(3))),
            strict);
  }

  /**
   * Whether a runtime role that is not bound by RLS refuses the start: when configured, and always
   * for a hosted (multi-workspace) deployment, as a missing MFA key does there (NFR-DEP-002).
   */
  static boolean isStrict(DatabaseRoleProperties properties, String topology) {
    return properties.requireRlsBoundRole() || HOSTED_TOPOLOGY.equalsIgnoreCase(topology);
  }

  /**
   * Warns, or with {@code strict} throws, when {@code role} is not bound by row-level security.
   *
   * @throws IllegalStateException in strict mode, for a role that bypasses RLS or owns the schema
   */
  static void check(RuntimeRole role, boolean strict) {
    if (role.isRlsBound()) {
      return;
    }
    List<String> reasons = new ArrayList<>(2);
    if (role.bypassesRls()) {
      reasons.add("is, or may act as, a superuser or BYPASSRLS role, which ignores every policy");
    }
    if (role.ownsSchema()) {
      reasons.add("owns, or may act as the owner of, the schema, which may switch RLS off");
    }
    String message =
        "The application connects to PostgreSQL as '"
            + role.name()
            + "', a role that "
            + String.join(" and ", reasons)
            + ", so workspace isolation is not enforced by the database. Connect as an RLS-bound"
            + " runtime role (see the README, \"Database roles\").";
    if (strict) {
      throw new IllegalStateException(
          message
              + " Refused by app.database.require-rls-bound-role or the hosted deployment"
              + " topology.");
    }
    LOG.warn(message);
  }

  /**
   * The role the application's own connections run as.
   *
   * @param bypassesRls it, or a role it may act as, is a superuser or {@code BYPASSRLS}
   * @param ownsSchema it, or a role it may act as, owns schema public or one of its tables
   */
  record RuntimeRole(String name, boolean bypassesRls, boolean ownsSchema) {

    boolean isRlsBound() {
      return !bypassesRls && !ownsSchema;
    }
  }
}
