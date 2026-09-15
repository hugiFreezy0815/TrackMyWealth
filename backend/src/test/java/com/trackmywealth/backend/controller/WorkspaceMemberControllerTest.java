package com.trackmywealth.backend.controller;

import static org.assertj.core.api.Assertions.assertThat;

import com.trackmywealth.backend.dto.AuthTokensResponse;
import com.trackmywealth.backend.dto.CreateUserRequest;
import com.trackmywealth.backend.dto.LoginRequest;
import com.trackmywealth.backend.dto.LoginResponse;
import com.trackmywealth.backend.dto.SetupAdministratorRequest;
import com.trackmywealth.backend.dto.UserSummaryResponse;
import com.trackmywealth.backend.dto.WorkspaceMemberSummaryResponse;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.client.RestTestClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * US-03-04/FR-HHL-015: a workspace must always retain at least one active member.
 *
 * <p>The DoD's two scenarios are {@link #deactivatingTheLastActiveMemberIsRejected} (one active
 * member) and {@link #deactivatingSelfWithAnotherActiveMemberRemainingSucceeds} (two active
 * members). Self-service only for now (see {@code WorkspaceMemberService}'s own Javadoc for why) -
 * {@link #deactivatingAnotherMembersAccountIsNotFound} covers that boundary.
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class WorkspaceMemberControllerTest {

  @Container
  static PostgreSQLContainer<?> postgres =
      new PostgreSQLContainer<>("postgres:16")
          .withDatabaseName("trackmywealth")
          .withUsername("trackmywealth")
          .withPassword("trackmywealth");

  @DynamicPropertySource
  static void datasourceProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", postgres::getJdbcUrl);
    registry.add("spring.datasource.username", postgres::getUsername);
    registry.add("spring.datasource.password", postgres::getPassword);
    registry.add("app.rate-limit.enabled", () -> "false");
  }

  private static final String SECOND_MEMBER_PASSWORD = "correct-horse-battery-staple";

  @LocalServerPort int port;

  @Autowired DataSource dataSource;

  @BeforeEach
  void cleanDatabase() throws Exception {
    // See InstitutionControllerTest's identical method for why this is plain per-table DELETEs,
    // not TRUNCATE ... CASCADE.
    try (Connection connection = dataSource.getConnection();
        Statement statement = connection.createStatement()) {
      for (String table :
          List.of(
              "admin_audit_log",
              "user_session",
              "refresh_token",
              "app_user",
              "workspace_member",
              "financial_institution",
              "workspace")) {
        statement.execute("DELETE FROM " + table);
      }
    }
  }

  @Test
  void deactivatingTheLastActiveMemberIsRejected() {
    // AC #1: a workspace with one active member - the bootstrap administrator is always that
    // workspace's sole member until a second is created.
    String token = bootstrapAdministrator();
    UUID selfMemberId = workspaceMemberIdForEmail("admin@example.com");

    client(token)
        .post()
        .uri("/api/v1/workspace-members/" + selfMemberId + "/deactivate")
        .exchange()
        .expectStatus()
        .isEqualTo(HttpStatus.CONFLICT);

    assertThat(memberStatus(selfMemberId)).isEqualTo("ACTIVE");
  }

  @Test
  void deactivatingSelfWithAnotherActiveMemberRemainingSucceeds() {
    // AC #2: a workspace with two active members - deactivating one succeeds and the other
    // remains ACTIVE.
    String adminToken = bootstrapAdministrator();
    UUID adminMemberId = workspaceMemberIdForEmail("admin@example.com");
    createSecondMember("partner@example.com");
    String partnerToken = login("partner@example.com", SECOND_MEMBER_PASSWORD);
    UUID partnerMemberId = workspaceMemberIdForEmail("partner@example.com");

    WorkspaceMemberSummaryResponse deactivated =
        client(partnerToken)
            .post()
            .uri("/api/v1/workspace-members/" + partnerMemberId + "/deactivate")
            .exchange()
            .expectStatus()
            .isOk()
            .expectBody(WorkspaceMemberSummaryResponse.class)
            .returnResult()
            .getResponseBody();

    assertThat(deactivated.status()).isEqualTo("INACTIVE");
    assertThat(deactivated.memberUntil()).isEqualTo(LocalDate.now());
    assertThat(memberStatus(partnerMemberId)).isEqualTo("INACTIVE");
    assertThat(memberStatus(adminMemberId)).isEqualTo("ACTIVE");
  }

  @Test
  void deactivatingAnotherMembersAccountIsNotFound() {
    // Self-service only for now: an active member with no grant mechanism yet available (US-03-03
    // is not merged - see WorkspaceMemberService's Javadoc) must not be able to deactivate someone
    // else, even another member of their own workspace.
    String adminToken = bootstrapAdministrator();
    UUID partnerMemberId = createSecondMember("partner@example.com");

    client(adminToken)
        .post()
        .uri("/api/v1/workspace-members/" + partnerMemberId + "/deactivate")
        .exchange()
        .expectStatus()
        .isEqualTo(HttpStatus.NOT_FOUND);

    assertThat(memberStatus(partnerMemberId)).isEqualTo("ACTIVE");
  }

  @Test
  void deactivatingAnUnknownMemberIsNotFound() {
    String token = bootstrapAdministrator();

    client(token)
        .post()
        .uri("/api/v1/workspace-members/" + UUID.randomUUID() + "/deactivate")
        .exchange()
        .expectStatus()
        .isEqualTo(HttpStatus.NOT_FOUND);
  }

  @Test
  void anonymousRequestIsUnauthorized() {
    RestTestClient.bindToServer()
        .baseUrl("http://localhost:%d".formatted(port))
        .build()
        .post()
        .uri("/api/v1/workspace-members/" + UUID.randomUUID() + "/deactivate")
        .exchange()
        .expectStatus()
        .isEqualTo(HttpStatus.UNAUTHORIZED);
  }

  private String memberStatus(UUID memberId) {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement =
            connection.prepareStatement("SELECT status FROM workspace_member WHERE id = ?")) {
      statement.setObject(1, memberId);
      try (ResultSet resultSet = statement.executeQuery()) {
        assertThat(resultSet.next()).as("workspace_member " + memberId).isTrue();
        return resultSet.getString("status");
      }
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  private UUID workspaceMemberIdForEmail(String email) {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement =
            connection.prepareStatement(
                "SELECT workspace_member_id FROM app_user WHERE email = ?")) {
      statement.setString(1, email);
      try (ResultSet resultSet = statement.executeQuery()) {
        assertThat(resultSet.next()).as("app_user with email " + email).isTrue();
        return (UUID) resultSet.getObject("workspace_member_id");
      }
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  private UUID createSecondMember(String email) {
    String adminToken = login("admin@example.com", "correct-horse-battery-staple");
    client(adminToken)
        .post()
        .uri("/api/v1/admin/users")
        .contentType(MediaType.APPLICATION_JSON)
        .body(new CreateUserRequest(email, SECOND_MEMBER_PASSWORD, "STANDARD_USER", "EN"))
        .exchange()
        .expectStatus()
        .isEqualTo(HttpStatus.CREATED)
        .expectBody(UserSummaryResponse.class);
    return workspaceMemberIdForEmail(email);
  }

  private String login(String email, String password) {
    return RestTestClient.bindToServer()
        .baseUrl("http://localhost:%d".formatted(port))
        .build()
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
        .tokens()
        .accessToken();
  }

  private String bootstrapAdministrator() {
    return RestTestClient.bindToServer()
        .baseUrl("http://localhost:%d".formatted(port))
        .build()
        .post()
        .uri("/api/v1/setup/administrator")
        .contentType(MediaType.APPLICATION_JSON)
        .body(
            new SetupAdministratorRequest(
                "admin@example.com", "correct-horse-battery-staple", "Test Workspace", "CHF"))
        .exchange()
        .expectStatus()
        .isEqualTo(HttpStatus.CREATED)
        .expectBody(AuthTokensResponse.class)
        .returnResult()
        .getResponseBody()
        .accessToken();
  }

  private RestTestClient client(String accessToken) {
    return RestTestClient.bindToServer()
        .baseUrl("http://localhost:%d".formatted(port))
        .defaultHeader("Authorization", "Bearer " + accessToken)
        .build();
  }
}
