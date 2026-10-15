package com.trackmywealth.backend.controller;

import static org.assertj.core.api.Assertions.assertThat;

import com.trackmywealth.backend.dto.AuthTokensResponse;
import com.trackmywealth.backend.dto.LoginRequest;
import com.trackmywealth.backend.dto.RefreshTokenRequest;
import com.trackmywealth.backend.dto.SetupAdministratorRequest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.UUID;
import org.junit.jupiter.api.Test;
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
 * End-to-end #321: migrations run as the owning user, but every real HTTP request (including its
 * JWT filter, transaction listener and service/repository calls) uses the NOSUPERUSER NOBYPASSRLS
 * runtime login provisioned before Flyway in the Testcontainers initialization script.
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class HardenedRuntimeAuthenticationHttpTest {

  private static final String ADMIN_USER = "trackmywealth";
  private static final String ADMIN_PASSWORD = "trackmywealth";
  private static final String PASSWORD = "correct-horse-battery-staple";

  @Container
  static PostgreSQLContainer<?> postgres =
      new PostgreSQLContainer<>("postgres:16")
          .withDatabaseName("trackmywealth")
          .withUsername(ADMIN_USER)
          .withPassword(ADMIN_PASSWORD)
          .withInitScript("hardened-runtime-init.sql");

  @DynamicPropertySource
  static void datasource(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", postgres::getJdbcUrl);
    registry.add("spring.datasource.username", () -> "trackmywealth_runtime");
    registry.add("spring.datasource.password", () -> "runtime-integration-password");
    registry.add("spring.flyway.url", postgres::getJdbcUrl);
    registry.add("spring.flyway.user", () -> ADMIN_USER);
    registry.add("spring.flyway.password", () -> ADMIN_PASSWORD);
    registry.add("app.database.auto-create", () -> "false");
    registry.add("app.rate-limit.enabled", () -> "false");
  }

  @LocalServerPort int port;

  @Test
  void realHttpPathUsesHardenedRoleAndInactiveMembershipRevokesAllCredentials()
      throws Exception {
    AuthTokensResponse original =
        client()
            .post()
            .uri("/api/v1/setup/administrator")
            .contentType(MediaType.APPLICATION_JSON)
            .body(
                new SetupAdministratorRequest(
                    "hardened@example.invalid", PASSWORD, "My Workspace", "CHF"))
            .exchange()
            .expectStatus()
            .isEqualTo(HttpStatus.CREATED)
            .expectBody(AuthTokensResponse.class)
            .returnResult()
            .getResponseBody();
    assertThat(original).isNotNull();

    client()
        .get()
        .uri("/api/v1/workspace")
        .header("Authorization", "Bearer " + original.accessToken())
        .exchange()
        .expectStatus()
        .isOk();

    AuthTokensResponse second =
        client()
            .post()
            .uri("/api/v1/auth/login")
            .contentType(MediaType.APPLICATION_JSON)
            .body(new LoginRequest("hardened@example.invalid", PASSWORD))
            .exchange()
            .expectStatus()
            .isOk()
            .expectBody(com.trackmywealth.backend.dto.LoginResponse.class)
            .returnResult()
            .getResponseBody()
            .tokens();
    assertThat(second).isNotNull();

    setMemberStatus("INACTIVE");

    client()
        .get()
        .uri("/api/v1/workspace")
        .header("Authorization", "Bearer " + original.accessToken())
        .exchange()
        .expectStatus()
        .isEqualTo(HttpStatus.UNAUTHORIZED);

    client()
        .post()
        .uri("/api/v1/auth/login")
        .contentType(MediaType.APPLICATION_JSON)
        .body(new LoginRequest("hardened@example.invalid", PASSWORD))
        .exchange()
        .expectStatus()
        .isEqualTo(HttpStatus.UNAUTHORIZED);

    client()
        .post()
        .uri("/api/v1/auth/refresh")
        .contentType(MediaType.APPLICATION_JSON)
        .body(new RefreshTokenRequest(second.refreshToken()))
        .exchange()
        .expectStatus()
        .isEqualTo(HttpStatus.UNAUTHORIZED);
  }

  private void setMemberStatus(String status) throws Exception {
    // The migration-only administrator is used to change membership outside the application's
    // normal authorization path. The HTTP requests themselves still run as the restricted role.
    try (Connection connection =
            DriverManager.getConnection(postgres.getJdbcUrl(), ADMIN_USER, ADMIN_PASSWORD);
        PreparedStatement query =
            connection.prepareStatement(
                "SELECT workspace_member_id FROM app_user WHERE email = 'hardened@example.invalid'")) {
      UUID memberId;
      try (ResultSet result = query.executeQuery()) {
        assertThat(result.next()).isTrue();
        memberId = (UUID) result.getObject(1);
      }
      try (PreparedStatement update =
          connection.prepareStatement("UPDATE workspace_member SET status = ? WHERE id = ?")) {
        update.setString(1, status);
        update.setObject(2, memberId);
        assertThat(update.executeUpdate()).isEqualTo(1);
      }
    }
  }

  private RestTestClient client() {
    return RestTestClient.bindToServer().baseUrl("http://localhost:%d".formatted(port)).build();
  }
}
