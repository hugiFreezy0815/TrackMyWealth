package com.trackmywealth.backend.testsupport;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.regex.Pattern;

/**
 * US-28-05 (#331): runs the role split exactly as {@code deploy/postgres/role-split.sh} does, from
 * the shipped SQL files, over JDBC as the PostgreSQL superuser: the cluster script, the database's
 * owner, then the database script. JDBC has no psql meta-commands, so the two {@code \getenv} lines
 * are dropped and the passwords they read are put in as quoted literals.
 */
public final class RoleSplitScripts {

  public static final String MIGRATION_ROLE = "trackmywealth_migration";
  public static final String RUNTIME_ROLE = "trackmywealth_runtime";

  // The scripts live outside the backend module; Maven runs the tests from backend/.
  private static final Path DEPLOY = Path.of("..", "deploy", "postgres");
  private static final Pattern GETENV = Pattern.compile("(?m)^\\\\getenv .*$");
  private static final Pattern PLAIN_NAME = Pattern.compile("[a-z_][a-z0-9_]*");
  private static final Pattern PLAIN_PASSWORD = Pattern.compile("[A-Za-z0-9_-]+");

  private RoleSplitScripts() {}

  /**
   * Splits {@code database} on the server of {@code jdbcUrl} (a URL naming any database there): an
   * existing one is handed to the migration role, a missing one is created for it.
   */
  public static void apply(
      String jdbcUrl,
      String superuser,
      String superuserPassword,
      String database,
      String migrationPassword,
      String runtimePassword)
      throws IOException, SQLException {
    require(database, PLAIN_NAME);
    require(migrationPassword, PLAIN_PASSWORD);
    require(runtimePassword, PLAIN_PASSWORD);
    String cluster =
        GETENV
            .matcher(read("role-split-cluster.sql"))
            .replaceAll("")
            .replace(":'migration_password'", "'" + migrationPassword + "'")
            .replace(":'runtime_password'", "'" + runtimePassword + "'");
    try (Connection admin = DriverManager.getConnection(jdbcUrl, superuser, superuserPassword);
        Statement statement = admin.createStatement()) {
      statement.execute(cluster);
      boolean exists;
      try (var result =
          statement.executeQuery("SELECT 1 FROM pg_database WHERE datname = '" + database + "'")) {
        exists = result.next();
      }
      statement.execute(
          exists
              ? "ALTER DATABASE \"" + database + "\" OWNER TO " + MIGRATION_ROLE
              : "CREATE DATABASE \"" + database + "\" OWNER " + MIGRATION_ROLE);
    }
    try (Connection admin =
            DriverManager.getConnection(
                withDatabase(jdbcUrl, database), superuser, superuserPassword);
        Statement statement = admin.createStatement()) {
      statement.execute(read("role-split-database.sql"));
    }
  }

  /** {@code jdbcUrl} pointing at {@code database} on the same server, parameters kept. */
  public static String withDatabase(String jdbcUrl, String database) {
    return jdbcUrl.replaceFirst("(jdbc:postgresql://[^/]+/)[^?]*", "$1" + database);
  }

  private static String read(String file) throws IOException {
    return Files.readString(DEPLOY.resolve(file), StandardCharsets.UTF_8);
  }

  private static void require(String value, Pattern pattern) {
    if (!pattern.matcher(value).matches()) {
      throw new IllegalArgumentException("Not a plain value: " + value);
    }
  }
}
