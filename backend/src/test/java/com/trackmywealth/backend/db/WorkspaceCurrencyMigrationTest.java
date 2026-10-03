package com.trackmywealth.backend.db;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.OffsetDateTime;
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
 * US-06-05: V59 gives every existing workspace the reporting currency of its oldest member with a
 * login, else CHF. Migrates to just before V59, seeds workspaces, then runs the rest.
 */
@Testcontainers
class WorkspaceCurrencyMigrationTest {

  @Container
  static PostgreSQLContainer<?> postgres =
      new PostgreSQLContainer<>("postgres:16")
          .withDatabaseName("trackmywealth")
          .withUsername("trackmywealth")
          .withPassword("trackmywealth");

  private static final OffsetDateTime JANUARY = OffsetDateTime.parse("2026-01-01T00:00:00Z");
  private static final OffsetDateTime FEBRUARY = OffsetDateTime.parse("2026-02-01T00:00:00Z");

  private static Connection connection;
  private static UUID twoLogins;
  private static UUID olderMemberWithoutLogin;
  private static UUID noLogin;

  @BeforeAll
  static void migrateSeedAndBackfill() throws Exception {
    // Whatever the last migration before V59 is, so this keeps working as earlier ones are added.
    flyway(lastVersionBefore("59")).migrate();
    // The Testcontainers role is the bootstrap superuser: it bypasses RLS for this fixture.
    connection =
        DriverManager.getConnection(
            postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());

    twoLogins = insertWorkspace("Two logins");
    insertUser(insertMember(twoLogins, FEBRUARY), "EUR");
    insertUser(insertMember(twoLogins, JANUARY), "USD");

    // The oldest member has no login (a dependent): the oldest one with a login decides.
    olderMemberWithoutLogin = insertWorkspace("Dependent first");
    insertMember(olderMemberWithoutLogin, JANUARY);
    insertUser(insertMember(olderMemberWithoutLogin, FEBRUARY), "JPY");

    noLogin = insertWorkspace("No login");
    insertMember(noLogin, JANUARY);

    flyway(null).migrate();
  }

  @AfterAll
  static void close() throws Exception {
    connection.close();
  }

  @Test
  void theOldestMemberWithALoginDecides() throws Exception {
    assertThat(currency(twoLogins)).isEqualTo("USD");
    assertThat(currency(olderMemberWithoutLogin)).isEqualTo("JPY");
  }

  @Test
  void aWorkspaceWithoutAnyLoginFallsBackToChf() throws Exception {
    assertThat(currency(noLogin)).isEqualTo("CHF");
  }

  // The column keeps its DEFAULT 'CHF' on purpose (see V59): the application always sets the
  // currency, the default only serves direct SQL inserts such as test fixtures.
  @Test
  void aWorkspaceInsertedWithoutACurrencyGetsChf() throws Exception {
    assertThat(currency(insertWorkspace("After V59"))).isEqualTo("CHF");
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

  private static UUID insertWorkspace(String name) throws Exception {
    try (PreparedStatement statement =
        connection.prepareStatement("INSERT INTO workspace (name) VALUES (?) RETURNING id")) {
      statement.setString(1, name);
      return single(statement);
    }
  }

  private static UUID insertMember(UUID workspace, OffsetDateTime createdAt) throws Exception {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "INSERT INTO workspace_member (workspace_id, display_name, created_at)"
                + " VALUES (?, 'Member', ?) RETURNING id")) {
      statement.setObject(1, workspace);
      statement.setObject(2, createdAt);
      return single(statement);
    }
  }

  private static void insertUser(UUID member, String reportingCurrency) throws Exception {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "INSERT INTO app_user (email, password_hash, workspace_member_id, reporting_currency)"
                + " VALUES (?, 'not-a-real-hash', ?, ?)")) {
      statement.setString(1, member + "@example.com");
      statement.setObject(2, member);
      statement.setString(3, reportingCurrency);
      statement.executeUpdate();
    }
  }

  private static String currency(UUID workspace) throws Exception {
    try (PreparedStatement statement =
        connection.prepareStatement("SELECT currency FROM workspace WHERE id = ?")) {
      statement.setObject(1, workspace);
      try (ResultSet resultSet = statement.executeQuery()) {
        resultSet.next();
        return resultSet.getString(1);
      }
    }
  }

  private static UUID single(PreparedStatement statement) throws Exception {
    try (ResultSet resultSet = statement.executeQuery()) {
      return resultSet.next() ? (UUID) resultSet.getObject(1) : null;
    }
  }
}
