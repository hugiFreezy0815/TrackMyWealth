package com.trackmywealth.backend.tenancy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.trackmywealth.backend.testsupport.RowLevelSecurityRole;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * #321: V81's two SECURITY DEFINER lookups, called by a real {@code NOSUPERUSER NOBYPASSRLS} role
 * before any workspace context exists - what they return, what they never return, and who may call
 * them at all. The request path through the application is {@code
 * HardenedRuntimeAuthenticationHttpTest}.
 */
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
  // Bound by RLS like ROLE, but never granted EXECUTE on the functions.
  private static final String UNGRANTED_ROLE = "auth_snapshot_ungranted";
  private static final String UNGRANTED_PASSWORD = "auth_snapshot_ungranted_password";
  private static final String SNAPSHOT =
      "SELECT workspace_id, workspace_member_status, session_status"
          + " FROM public.auth_user_snapshot(?, ?)";
  private static final String MEMBER_STATUS = "SELECT public.auth_workspace_member_status(?)";

  private static UUID user;
  private static UUID member;
  private static UUID workspace;
  private static UUID session;
  private static UUID otherUser;
  private static UUID otherSession;
  private static UUID userWithoutMembership;

  @BeforeAll
  static void setup() throws Exception {
    Flyway.configure()
        .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
        .load()
        .migrate();
    try (Connection admin = connect(postgres.getUsername(), postgres.getPassword())) {
      RowLevelSecurityRole.createWithLogin(admin, ROLE, PASSWORD);
      RowLevelSecurityRole.createWithLogin(admin, UNGRANTED_ROLE, UNGRANTED_PASSWORD);
      try (Statement statement = admin.createStatement()) {
        statement.execute(
            "GRANT EXECUTE ON FUNCTION public.auth_user_snapshot(UUID, UUID) TO " + ROLE);
        statement.execute(
            "GRANT EXECUTE ON FUNCTION public.auth_workspace_member_status(UUID) TO " + ROLE);
      }
      // One transaction, so each member has its login when it commits (V110).
      admin.setAutoCommit(false);
      workspace = insertWorkspace(admin);
      member = insertMember(admin, workspace);
      user = insertUser(admin, "synthetic@example.invalid", member);
      session = insertSession(admin, user);
      UUID otherWorkspace = insertWorkspace(admin);
      otherUser = insertUser(admin, "other@example.invalid", insertMember(admin, otherWorkspace));
      otherSession = insertSession(admin, otherUser);
      userWithoutMembership = insertUser(admin, "admin-only@example.invalid", null);
      admin.commit();
    }
  }

  @Test
  void theSnapshotReadsTheMembershipAndThePresentedSessionBeforeAnyWorkspaceContext()
      throws Exception {
    try (Connection runtime = connect(ROLE, PASSWORD);
        PreparedStatement query = runtime.prepareStatement(SNAPSHOT)) {
      query.setObject(1, user);
      query.setObject(2, session);
      try (ResultSet result = query.executeQuery()) {
        assertThat(result.next()).isTrue();
        assertThat(result.getObject(1)).isEqualTo(workspace);
        assertThat(result.getString(2)).isEqualTo("ACTIVE");
        assertThat(result.getString(3)).isEqualTo("ACTIVE");
        assertThat(result.next()).isFalse();
      }
    }
  }

  @Test
  void theSnapshotReturnsNothingWithoutTheUsersOwnExistingSession() throws Exception {
    try (Connection runtime = connect(ROLE, PASSWORD);
        PreparedStatement query = runtime.prepareStatement(SNAPSHOT)) {
      query.setObject(1, user);
      for (UUID notThisUsersSession : new UUID[] {UUID.randomUUID(), otherSession, null}) {
        query.setObject(2, notThisUsersSession);
        try (ResultSet result = query.executeQuery()) {
          assertThat(result.next()).as("session %s", notThisUsersSession).isFalse();
        }
      }
    }
  }

  @Test
  void theMemberStatusFunctionReturnsOnlyTheStatusAndNullWithoutAMembership() throws Exception {
    assertThat(memberStatus(user)).isEqualTo("ACTIVE");
    assertThat(memberStatus(userWithoutMembership)).isNull();
    assertThat(memberStatus(UUID.randomUUID())).isNull();

    setMemberStatus("INACTIVE");
    try {
      assertThat(memberStatus(user)).isEqualTo("INACTIVE");
    } finally {
      setMemberStatus("ACTIVE");
    }
  }

  @Test
  void aRoleWithoutTheExplicitGrantCannotCallEitherFunction() throws Exception {
    try (Connection ungranted = connect(UNGRANTED_ROLE, UNGRANTED_PASSWORD)) {
      try (PreparedStatement query = ungranted.prepareStatement(SNAPSHOT)) {
        query.setObject(1, user);
        query.setObject(2, session);
        assertThatThrownBy(query::executeQuery)
            .isInstanceOf(SQLException.class)
            .hasMessageContaining("permission denied for function auth_user_snapshot");
      }
      try (PreparedStatement query = ungranted.prepareStatement(MEMBER_STATUS)) {
        query.setObject(1, user);
        assertThatThrownBy(query::executeQuery)
            .isInstanceOf(SQLException.class)
            .hasMessageContaining("permission denied for function auth_workspace_member_status");
      }
    }
  }

  @Test
  void rowLevelSecurityStillHidesMembersAndWorkspacesFromTheRuntimeRole() throws Exception {
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

  private static String memberStatus(UUID userId) throws Exception {
    try (Connection runtime = connect(ROLE, PASSWORD);
        PreparedStatement query = runtime.prepareStatement(MEMBER_STATUS)) {
      query.setObject(1, userId);
      try (ResultSet result = query.executeQuery()) {
        assertThat(result.next()).isTrue();
        return result.getString(1);
      }
    }
  }

  private static void setMemberStatus(String status) throws Exception {
    try (Connection admin = connect(postgres.getUsername(), postgres.getPassword());
        PreparedStatement update =
            admin.prepareStatement("UPDATE workspace_member SET status = ? WHERE id = ?")) {
      update.setString(1, status);
      update.setObject(2, member);
      assertThat(update.executeUpdate()).isOne();
    }
  }

  private static UUID insertWorkspace(Connection admin) throws SQLException {
    UUID id = UUID.randomUUID();
    try (PreparedStatement statement =
        admin.prepareStatement("INSERT INTO workspace (id, name) VALUES (?, 'Synthetic')")) {
      statement.setObject(1, id);
      statement.executeUpdate();
    }
    return id;
  }

  private static UUID insertMember(Connection admin, UUID workspaceId) throws SQLException {
    UUID id = UUID.randomUUID();
    try (PreparedStatement statement =
        admin.prepareStatement(
            "INSERT INTO workspace_member (id, workspace_id, display_name)"
                + " VALUES (?, ?, 'Synthetic member')")) {
      statement.setObject(1, id);
      statement.setObject(2, workspaceId);
      statement.executeUpdate();
    }
    return id;
  }

  private static UUID insertUser(Connection admin, String email, UUID memberId)
      throws SQLException {
    UUID id = UUID.randomUUID();
    try (PreparedStatement statement =
        admin.prepareStatement(
            "INSERT INTO app_user (id, email, password_hash, workspace_member_id)"
                + " VALUES (?, ?, 'synthetic-hash', ?)")) {
      statement.setObject(1, id);
      statement.setString(2, email);
      statement.setObject(3, memberId);
      statement.executeUpdate();
    }
    return id;
  }

  private static UUID insertSession(Connection admin, UUID userId) throws SQLException {
    UUID id = UUID.randomUUID();
    try (PreparedStatement statement =
        admin.prepareStatement(
            "INSERT INTO user_session (id, user_id, status) VALUES (?, ?, 'ACTIVE')")) {
      statement.setObject(1, id);
      statement.setObject(2, userId);
      statement.executeUpdate();
    }
    return id;
  }

  private static Connection connect(String username, String password) throws SQLException {
    return DriverManager.getConnection(postgres.getJdbcUrl(), username, password);
  }
}
