package com.trackmywealth.backend.testsupport;

import com.trackmywealth.backend.dto.AuthTokensResponse;
import com.trackmywealth.backend.dto.CreateUserRequest;
import com.trackmywealth.backend.dto.LoginRequest;
import com.trackmywealth.backend.dto.LoginResponse;
import com.trackmywealth.backend.dto.SetupAdministratorRequest;
import com.trackmywealth.backend.dto.UserRoleValues;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.test.web.servlet.client.RestTestClient;

public final class WorkspaceLoginFixtures {
  private static final String FIXTURE_ADMIN_EMAIL = "fixture-admin@example.com";
  private static final String FIXTURE_ADMIN_PASSWORD = "fixture-admin-password";

  private WorkspaceLoginFixtures() {}

  public static AuthTokensResponse bootstrapStandardUser(
      RestTestClient client, String email, String password, String workspaceName, String currency) {
    createStandardUser(
        client,
        bootstrapAdministrator(client).accessToken(),
        email,
        password,
        workspaceName,
        currency);
    return login(client, email, password);
  }

  public static AuthTokensResponse loginAdministrator(RestTestClient client) {
    return login(client, FIXTURE_ADMIN_EMAIL, FIXTURE_ADMIN_PASSWORD);
  }

  private static AuthTokensResponse login(RestTestClient client, String email, String password) {
    return client
        .post()
        .uri("/api/v1/auth/login")
        .contentType(MediaType.APPLICATION_JSON)
        .body(new LoginRequest(email, password))
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody(LoginResponse.class)
        .returnResult()
        .getResponseBody()
        .tokens();
  }

  public static AuthTokensResponse bootstrapAdministrator(RestTestClient client) {
    return client
        .post()
        .uri("/api/v1/setup/administrator")
        .contentType(MediaType.APPLICATION_JSON)
        .body(
            new SetupAdministratorRequest(FIXTURE_ADMIN_EMAIL, FIXTURE_ADMIN_PASSWORD, null, "CHF"))
        .exchange()
        .expectStatus()
        .isCreated()
        .expectBody(AuthTokensResponse.class)
        .returnResult()
        .getResponseBody();
  }

  public static void createStandardUser(
      RestTestClient client,
      String adminToken,
      String email,
      String password,
      String workspaceName,
      String currency) {
    client
        .post()
        .uri("/api/v1/admin/users")
        .header("Authorization", "Bearer " + adminToken)
        .contentType(MediaType.APPLICATION_JSON)
        .body(
            new CreateUserRequest(
                email, password, UserRoleValues.STANDARD_USER, "EN", workspaceName, currency))
        .exchange()
        .expectStatus()
        .isCreated();
  }

  /** Seed a shared workspace for membership tests. Provisioning creates separate workspaces. */
  public static UUID addMember(
      DataSource dataSource, String existingEmail, String email, String password) {
    UUID memberId = UUID.randomUUID();
    try (Connection connection = dataSource.getConnection()) {
      connection.setAutoCommit(false);
      try (PreparedStatement member =
              connection.prepareStatement(
                  """
              INSERT INTO workspace_member (id, workspace_id, display_name)
              SELECT ?, m.workspace_id, ?
              FROM app_user u JOIN workspace_member m ON u.workspace_member_id = m.id
              WHERE u.email = ?
              """);
          PreparedStatement user =
              connection.prepareStatement(
                  """
              INSERT INTO app_user (email, password_hash, workspace_member_id) VALUES (?, ?, ?)
              """)) {
        member.setObject(1, memberId);
        member.setString(2, email.substring(0, email.indexOf('@')));
        member.setString(3, existingEmail);
        if (member.executeUpdate() != 1) {
          throw new IllegalStateException("Shared-workspace fixture requires an existing member.");
        }
        user.setString(1, email);
        user.setString(2, Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8().encode(password));
        user.setObject(3, memberId);
        user.executeUpdate();
      }
      connection.commit();
      return memberId;
    } catch (SQLException e) {
      throw new IllegalStateException("Could not create a shared-workspace fixture member.", e);
    }
  }
}
