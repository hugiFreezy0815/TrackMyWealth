package com.trackmywealth.backend.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
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
 * US-03-06 (#389): V110 makes membership the only access grant and every member a user. A database
 * it cannot convert as is stops with a message naming the rows rather than being repaired silently:
 * first a member without a login, then a member with two logins. Once those are resolved it
 * migrates, drops the ownership and grant tables and the dependent flag.
 */
@Testcontainers
class WorkspaceMembershipMigrationTest {

  @Container
  static PostgreSQLContainer<?> postgres =
      new PostgreSQLContainer<>("postgres:16")
          .withDatabaseName("trackmywealth")
          .withUsername("trackmywealth")
          .withPassword("trackmywealth");

  private static Connection connection;

  @BeforeAll
  static void migrateToJustBeforeV110() throws Exception {
    flyway(lastVersionBefore("110")).migrate();
    // The Testcontainers role is the bootstrap superuser: it bypasses RLS for this fixture.
    connection =
        DriverManager.getConnection(
            postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
  }

  @AfterAll
  static void close() throws Exception {
    connection.close();
  }

  @Test
  void refusesMembersWithoutExactlyOneLoginThenMigrates() throws Exception {
    UUID workspace = insert("INSERT INTO workspace (name) VALUES ('Legacy') RETURNING id");
    UUID withLogin = member(workspace);
    login(withLogin, "member@example.com");
    UUID dependent = member(workspace);

    assertThatThrownBy(() -> flyway(null).migrate())
        .hasMessageContaining("workspace_member_requires_login")
        .hasMessageContaining(dependent.toString());

    execute("DELETE FROM workspace_member WHERE id = '" + dependent + "'");
    login(withLogin, "second-login@example.com");

    assertThatThrownBy(() -> flyway(null).migrate())
        .hasMessageContaining("workspace_member_single_login")
        .hasMessageContaining(withLogin.toString());

    execute("DELETE FROM app_user WHERE email = 'second-login@example.com'");
    flyway(null).migrate();

    assertThat(exists("SELECT to_regclass('public.account_ownership') IS NOT NULL")).isFalse();
    assertThat(exists("SELECT to_regclass('public.sharing_grant') IS NOT NULL")).isFalse();
    assertThat(
            exists(
                "SELECT EXISTS (SELECT 1 FROM information_schema.columns"
                    + " WHERE table_name = 'workspace_member' AND column_name = 'is_dependent')"))
        .isFalse();
    // The member that had its one login keeps it.
    assertThat(
            exists(
                "SELECT EXISTS (SELECT 1 FROM app_user WHERE workspace_member_id = '"
                    + withLogin
                    + "')"))
        .isTrue();

    // After V110 a member cannot lose its login later either: an unlink that leaves the member in
    // place is refused at commit, and one that removes the member with it is not.
    assertThatThrownBy(
            () ->
                execute(
                    "UPDATE app_user SET workspace_member_id = NULL"
                        + " WHERE email = 'member@example.com'"))
        .hasMessageContaining("workspace_member_requires_login")
        .hasMessageContaining(withLogin.toString());
    connection.setAutoCommit(false);
    try {
      execute("UPDATE app_user SET workspace_member_id = NULL WHERE email = 'member@example.com'");
      execute("DELETE FROM workspace_member WHERE id = '" + withLogin + "'");
      connection.commit();
    } finally {
      connection.setAutoCommit(true);
    }
    assertThat(exists("SELECT EXISTS (SELECT 1 FROM workspace_member)")).isFalse();
  }

  private static UUID member(UUID workspace) throws Exception {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "INSERT INTO workspace_member (workspace_id, display_name) VALUES (?, 'Member')"
                + " RETURNING id")) {
      statement.setObject(1, workspace);
      try (ResultSet result = statement.executeQuery()) {
        result.next();
        return result.getObject(1, UUID.class);
      }
    }
  }

  private static void login(UUID member, String email) throws Exception {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "INSERT INTO app_user (email, password_hash, workspace_member_id)"
                + " VALUES (?, 'not-a-real-hash', ?)")) {
      statement.setString(1, email);
      statement.setObject(2, member);
      statement.executeUpdate();
    }
  }

  private static UUID insert(String sql) throws Exception {
    try (PreparedStatement statement = connection.prepareStatement(sql);
        ResultSet result = statement.executeQuery()) {
      result.next();
      return result.getObject(1, UUID.class);
    }
  }

  private static void execute(String sql) throws Exception {
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.executeUpdate();
    }
  }

  private static boolean exists(String sql) throws Exception {
    try (PreparedStatement statement = connection.prepareStatement(sql);
        ResultSet result = statement.executeQuery()) {
      result.next();
      return result.getBoolean(1);
    }
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
}
