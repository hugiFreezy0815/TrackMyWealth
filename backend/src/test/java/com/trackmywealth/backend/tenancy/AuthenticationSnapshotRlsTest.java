package com.trackmywealth.backend.tenancy;

import static org.assertj.core.api.Assertions.assertThat;

import com.trackmywealth.backend.testsupport.RowLevelSecurityRole;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** #321: authentication before principal initialization under a real non-bypass RLS role. */
@Testcontainers
class AuthenticationSnapshotRlsTest {

  @Container
  static PostgreSQLContainer<?> postgres =
      new PostgreSQLContainer<>("postgres:16")
          .withDatabaseName("trackmywealth")
          .withUsername("trackmywealth")
          .withPassword("trackmywealth");

  private static final String ROLE = "auth_snapshot_runtime";
  private static final String PASSWORD = "auth_snapshot_runtime_password";
  private static UUID user;
  private static UUID member;
  private static UUID workspace;
  private static UUID session;

  @BeforeAll
  static void setup() throws Exception {
    Flyway.configure()
        .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
        .load()
        .migrate();
    try (Connection admin = connect(postgres.getUsername(), postgres.getPassword())) {
      RowLevelSecurityRole.createWithLogin(admin, ROLE, PASSWORD);
      try (Statement statement = admin.createStatement()) {
        statement.execute(
            "GRANT EXECUTE ON FUNCTION public.auth_user_snapshot(UUID, UUID) TO " + ROLE);
      }
      workspace = UUID.randomUUID();
      member = UUID.randomUUID();
      user = UUID.randomUUID();
      session = UUID.randomUUID();
      try (PreparedStatement statement =
          admin.prepareStatement("INSERT INTO workspace (id, name) VALUES (?, 'Synthetic')")) {
        statement.setObject(1, workspace);
        statement.executeUpdate();
      }
      try (PreparedStatement statement =
          admin.prepareStatement(
              "INSERT INTO workspace_member (id, workspace_id, display_name) "
                  + "VALUES (?, ?, 'Synthetic member')")) {
        statement.setObject(1, member);
        statement.setObject(2, workspace);
        statement.executeUpdate();
      }
      try (PreparedStatement statement =
          admin.prepareStatement(
              "INSERT INTO app_user (id, email, password_hash, workspace_member_id) "
                  + "VALUES (?, 'synthetic@example.invalid', 'synthetic-hash', ?)")) {
        statement.setObject(1, user);
        statement.setObject(2, member);
        statement.executeUpdate();
      }
      try (PreparedStatement statement =
          admin.prepareStatement(
              "INSERT INTO user_session (id, user_id, status) VALUES (?, ?, 'ACTIVE')")) {
        statement.setObject(1, session);
        statement.setObject(2, user);
        statement.executeUpdate();
      }
    }
  }

  @Test
  void membershipAndSessionAreReadableBeforeWorkspaceContextExists() throws Exception {
    try (Connection runtime = connect(ROLE, PASSWORD)) {
      try (PreparedStatement query =
          runtime.prepareStatement(
              "SELECT workspace_id, workspace_member_status, session_status "
                  + "FROM public.auth_user_snapshot(?, ?)")) {
        query.setObject(1, user);
        query.setObject(2, session);
        try (ResultSet result = query.executeQuery()) {
          assertThat(result.next()).isTrue();
          assertThat(result.getObject(1)).isEqualTo(workspace);
          assertThat(result.getString(2)).isEqualTo("ACTIVE");
          assertThat(result.getString(3)).isEqualTo("ACTIVE");
          assertThat(result.next()).isFalse();
        }
        query.setObject(2, UUID.randomUUID());
        try (ResultSet result = query.executeQuery()) {
          assertThat(result.next()).isFalse();
        }
        query.setObject(2, null);
        try (ResultSet result = query.executeQuery()) {
          assertThat(result.next()).isTrue();
          assertThat(result.getString(2)).isEqualTo("ACTIVE");
        }
      }
    }
  }

  @Test
  void inactiveMemberIsVisibleToEligibilityCheckButNotAuthorizedByStatus() throws Exception {
    try (Connection admin = connect(postgres.getUsername(), postgres.getPassword());
        PreparedStatement update =
            admin.prepareStatement("UPDATE workspace_member SET status = 'INACTIVE' WHERE id = ?")) {
      update.setObject(1, member);
      update.executeUpdate();
    }
    try (Connection runtime = connect(ROLE, PASSWORD);
        PreparedStatement query =
            runtime.prepareStatement(
                "SELECT workspace_member_status FROM public.auth_user_snapshot(?, NULL)")) {
      query.setObject(1, user);
      try (ResultSet result = query.executeQuery()) {
        assertThat(result.next()).isTrue();
        assertThat(result.getString(1)).isEqualTo("INACTIVE");
      }
    } finally {
      try (Connection admin = connect(postgres.getUsername(), postgres.getPassword());
          PreparedStatement update =
              admin.prepareStatement("UPDATE workspace_member SET status = 'ACTIVE' WHERE id = ?")) {
        update.setObject(1, member);
        update.executeUpdate();
      }
    }
  }

  @Test
  void rlsStillHidesMemberAndWorkspaceTablesFromRuntimeRole() throws Exception {
    try (Connection runtime = connect(ROLE, PASSWORD);
        Statement statement = runtime.createStatement()) {
      try (ResultSet rows = statement.executeQuery("SELECT id FROM workspace_member")) {
        assertThat(rows.next()).isFalse();
      }
      try (ResultSet rows = statement.executeQuery("SELECT id FROM workspace")) {
        assertThat(rows.next()).isFalse();
      }
    }
  }

  private static Connection connect(String username, String password) throws Exception {
    return DriverManager.getConnection(postgres.getJdbcUrl(), username, password);
  }
}
