package com.trackmywealth.backend.controller;

import static org.assertj.core.api.Assertions.assertThat;

import com.trackmywealth.backend.dto.AuthTokensResponse;
import com.trackmywealth.backend.dto.CreateUserRequest;
import com.trackmywealth.backend.dto.CurrentUserResponse;
import com.trackmywealth.backend.dto.LoginRequest;
import com.trackmywealth.backend.dto.LoginResponse;
import com.trackmywealth.backend.dto.SetupAdministratorRequest;
import com.trackmywealth.backend.dto.UserSummaryResponse;
import com.trackmywealth.backend.testsupport.LedgerCleanup;
import java.sql.Connection;
import java.sql.Statement;
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
 * US-02-06: the verified identity the mobile app gates admin-only screens on, and the plain 401
 * (never a 500) for a caller without a valid token - the response the client refreshes on.
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class CurrentUserControllerTest {

  private static final String PASSWORD = "correct-horse-battery-staple";

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

  @LocalServerPort int port;

  @Autowired DataSource dataSource;

  @BeforeEach
  void cleanDatabase() throws Exception {
    try (Connection connection = dataSource.getConnection();
        Statement statement = connection.createStatement()) {
      statement.execute(
          LedgerCleanup.withGuardsOff(
              "TRUNCATE TABLE admin_audit_log, user_session, refresh_token, app_user,"
                  + " workspace_member, financial_institution, workspace RESTART IDENTITY CASCADE"));
    }
  }

  @Test
  void anAdministratorSeesTheirOwnVerifiedRole() {
    String adminToken = bootstrapAdministrator();

    CurrentUserResponse me = currentUser(adminToken);

    assertThat(me.userId()).isNotNull();
    assertThat(me.role()).isEqualTo("SYSTEM_ADMINISTRATOR");
    assertThat(me.language()).isEqualTo("EN");
  }

  @Test
  void aStandardUserSeesTheirOwnIdentityNotTheAdministrators() {
    String adminToken = bootstrapAdministrator();
    CurrentUserResponse admin = currentUser(adminToken);
    createStandardUser(adminToken, "member@example.com");

    CurrentUserResponse me = currentUser(login("member@example.com").accessToken());

    assertThat(me.role()).isEqualTo("STANDARD_USER");
    assertThat(me.language()).isEqualTo("DE");
    assertThat(me.userId()).isNotEqualTo(admin.userId());
  }

  @Test
  void withoutATokenTheAnswerIsAPlain401() {
    client()
        .get()
        .uri("/api/v1/users/me")
        .exchange()
        .expectStatus()
        .isEqualTo(HttpStatus.UNAUTHORIZED);
  }

  @Test
  void anInvalidTokenIsAPlain401NotAServerError() {
    client()
        .get()
        .uri("/api/v1/users/me")
        .header("Authorization", "Bearer not-a-jwt")
        .exchange()
        .expectStatus()
        .isEqualTo(HttpStatus.UNAUTHORIZED);
  }

  private CurrentUserResponse currentUser(String accessToken) {
    return client()
        .get()
        .uri("/api/v1/users/me")
        .header("Authorization", "Bearer " + accessToken)
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody(CurrentUserResponse.class)
        .returnResult()
        .getResponseBody();
  }

  private AuthTokensResponse login(String email) {
    return client()
        .post()
        .uri("/api/v1/auth/login")
        .contentType(MediaType.APPLICATION_JSON)
        .body(new LoginRequest(email, PASSWORD))
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody(LoginResponse.class)
        .returnResult()
        .getResponseBody()
        .tokens();
  }

  private String bootstrapAdministrator() {
    return client()
        .post()
        .uri("/api/v1/setup/administrator")
        .contentType(MediaType.APPLICATION_JSON)
        .body(new SetupAdministratorRequest("admin@example.com", PASSWORD, "Test Workspace", "CHF"))
        .exchange()
        .expectStatus()
        .isEqualTo(HttpStatus.CREATED)
        .expectBody(AuthTokensResponse.class)
        .returnResult()
        .getResponseBody()
        .accessToken();
  }

  private void createStandardUser(String adminToken, String email) {
    client()
        .post()
        .uri("/api/v1/admin/users")
        .header("Authorization", "Bearer " + adminToken)
        .contentType(MediaType.APPLICATION_JSON)
        .body(new CreateUserRequest(email, PASSWORD, "STANDARD_USER", "DE"))
        .exchange()
        .expectStatus()
        .isEqualTo(HttpStatus.CREATED)
        .expectBody(UserSummaryResponse.class);
  }

  private RestTestClient client() {
    return RestTestClient.bindToServer().baseUrl("http://localhost:%d".formatted(port)).build();
  }
}
