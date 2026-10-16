package com.trackmywealth.backend.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * US-28-05 (#331): the database role split. The application's datasource ({@code DB_USERNAME}) is
 * the runtime role; when {@code migrationUsername} is set, Flyway runs as that separate migration
 * role instead ({@link DatabaseRoleConfig}). Unset, one role does both, as before the split.
 *
 * @param migrationUsername the role that owns the schema and runs Flyway ({@code
 *     DB_MIGRATION_USERNAME}); blank: the datasource's own role
 * @param migrationPassword its password ({@code DB_MIGRATION_PASSWORD})
 * @param requireRlsBoundRole refuse to start when the runtime role is not bound by row-level
 *     security ({@code DB_REQUIRE_RLS_BOUND_ROLE}, see {@link DatabaseRoleConfig}); otherwise that
 *     is only a startup warning
 */
@ConfigurationProperties(prefix = "app.database")
public record DatabaseRoleProperties(
    String migrationUsername, String migrationPassword, boolean requireRlsBoundRole) {

  /** Whether Flyway runs as a role of its own. */
  public boolean hasMigrationRole() {
    return migrationUsername != null && !migrationUsername.isBlank();
  }

  // Never print the password: this record is bound from the environment and may be logged.
  @Override
  public String toString() {
    return "DatabaseRoleProperties[migrationUsername="
        + migrationUsername
        + ", requireRlsBoundRole="
        + requireRlsBoundRole
        + "]";
  }
}
