package com.trackmywealth.backend.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
class AdministratorWorkspaceBoundaryMigrationTest {

  @Container
  static PostgreSQLContainer<?> postgres =
      new PostgreSQLContainer<>("postgres:16")
          .withDatabaseName("trackmywealth")
          .withUsername("trackmywealth")
          .withPassword("trackmywealth");

  @Test
  void rejectsALegacyLinkWithoutChangingItAndEnforcesTheBoundaryOnACleanUpgrade()
      throws SQLException {
    flyway("126").migrate();
    UUID adminId = UUID.randomUUID();
    UUID memberId = UUID.randomUUID();
    UUID workspaceId = UUID.randomUUID();
    try (var connection =
            DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        var statement = connection.createStatement()) {
      connection.setAutoCommit(false);
      statement.executeUpdate("INSERT INTO workspace (id) VALUES ('" + workspaceId + "')");
      statement.executeUpdate(
          "INSERT INTO workspace_member (id, workspace_id, display_name) VALUES ('"
              + memberId
              + "', '"
              + workspaceId
              + "', 'Fixture member')");
      statement.executeUpdate(
          "INSERT INTO app_user (id, email, password_hash, role, workspace_member_id) VALUES ('"
              + adminId
              + "', 'fixture@example.com', 'unused', 'SYSTEM_ADMINISTRATOR', '"
              + memberId
              + "')");
      connection.commit();

      assertThatThrownBy(() -> flyway("127").migrate())
          .isInstanceOf(FlywayException.class)
          .hasMessageContaining(adminId.toString())
          .hasMessageContaining(memberId.toString());
      try (var rows = statement.executeQuery("SELECT workspace_member_id FROM app_user")) {
        assertThat(rows.next()).isTrue();
        assertThat(rows.getObject(1, UUID.class)).isEqualTo(memberId);
      }
      statement.executeUpdate("UPDATE app_user SET role = 'STANDARD_USER'");
      connection.commit();
      flyway("127").migrate();
      assertThatThrownBy(
              () -> statement.executeUpdate("UPDATE app_user SET role = 'SYSTEM_ADMINISTRATOR'"))
          .isInstanceOf(SQLException.class)
          .hasMessageContaining("chk_app_user_admin_has_no_workspace_member");
      connection.rollback();
    }
  }

  private static Flyway flyway(String target) {
    return Flyway.configure()
        .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
        .target(target)
        .load();
  }
}
