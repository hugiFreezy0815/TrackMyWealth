package com.trackmywealth.backend.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * US-03-07: V109 makes the workspace name optional, trims names stored before it and refuses a
 * blank one. Migrates to just before V109, seeds workspaces, then applies V109.
 */
@Testcontainers
class WorkspaceNameMigrationTest {

  @Container
  static PostgreSQLContainer<?> postgres =
      new PostgreSQLContainer<>("postgres:16")
          .withDatabaseName("trackmywealth")
          .withUsername("trackmywealth")
          .withPassword("trackmywealth");

  private static Connection connection;
  private static UUID padded;
  private static UUID blank;
  private static UUID plain;
  private static int plainVersion;

  @BeforeAll
  static void migrateSeedAndBackfill() throws Exception {
    flyway(lastVersionBefore("109")).migrate();
    // The Testcontainers role is the bootstrap superuser: it bypasses RLS for this fixture.
    connection =
        DriverManager.getConnection(
            postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());

    padded = insertWorkspace("  Hugi  ");
    blank = insertWorkspace("   ");
    plain = insertWorkspace("Family Hug");
    plainVersion = version(plain);

    flyway("109").migrate();
  }

  @AfterAll
  static void close() throws Exception {
    connection.close();
  }

  @Test
  void existingNamesAreTrimmedAndABlankOneBecomesNull() throws Exception {
    assertThat(name(padded)).isEqualTo("Hugi");
    assertThat(name(blank)).isNull();
  }

  @Test
  void aNameThatNeedsNoChangeIsNotTouched() throws Exception {
    assertThat(name(plain)).isEqualTo("Family Hug");
    assertThat(version(plain)).isEqualTo(plainVersion);
  }

  @Test
  void aWorkspaceMayHaveNoNameButNotABlankOne() throws Exception {
    assertThat(name(insertWorkspace(null))).isNull();
    assertThatThrownBy(() -> insertWorkspace("  "))
        .isInstanceOf(SQLException.class)
        .hasMessageContaining("chk_workspace_name_not_blank");
  }

  private static String lastVersionBefore(String version) {
    MigrationVersion limit = MigrationVersion.fromVersion(version);
    return Arrays.stream(flyway(null).info().all())
        .map(MigrationInfo::getVersion)
        .filter(candidate -> candidate.compareTo(limit) < 0)
        .max(MigrationVersion::compareTo)
        .orElseThrow()
        .getVersion();
  }

  private static Flyway flyway(String target) {
    var configuration =
        Flyway.configure()
            .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    if (target != null) {
      configuration.target(target);
    }
    return configuration.load();
  }

  private static UUID insertWorkspace(String name) throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement("INSERT INTO workspace (name) VALUES (?) RETURNING id")) {
      statement.setString(1, name);
      try (ResultSet rs = statement.executeQuery()) {
        rs.next();
        return rs.getObject(1, UUID.class);
      }
    }
  }

  private static String name(UUID workspace) throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement("SELECT name FROM workspace WHERE id = ?")) {
      statement.setObject(1, workspace);
      try (ResultSet rs = statement.executeQuery()) {
        rs.next();
        return rs.getString(1);
      }
    }
  }

  private static int version(UUID workspace) throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement("SELECT version FROM workspace WHERE id = ?")) {
      statement.setObject(1, workspace);
      try (ResultSet rs = statement.executeQuery()) {
        rs.next();
        return rs.getInt(1);
      }
    }
  }
}
