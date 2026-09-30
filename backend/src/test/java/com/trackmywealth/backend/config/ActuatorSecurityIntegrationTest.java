package com.trackmywealth.backend.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.trackmywealth.backend.dto.AuthTokensResponse;
import com.trackmywealth.backend.dto.CreateUserRequest;
import com.trackmywealth.backend.dto.LoginRequest;
import com.trackmywealth.backend.dto.LoginResponse;
import com.trackmywealth.backend.dto.SetupAdministratorRequest;
import java.sql.Connection;
import java.sql.Statement;
import java.util.List;
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
 * Security contract for Actuator endpoints (#191, NFR-SEC-001): deployment probes stay public,
 * while everything operational - Flyway's schema and migration metadata, the endpoint index, and
 * health's details - is restricted to system administrators. Each endpoint is checked for all three
 * personas: anonymous, {@code STANDARD_USER} and {@code SYSTEM_ADMINISTRATOR}.
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ActuatorSecurityIntegrationTest {

  private static final String PASSWORD = "correct-horse-battery-staple";
  private static final String MEMBER = "standard@example.com";

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
      for (String sql :
          List.of(
              "DELETE FROM admin_audit_log",
              "DELETE FROM user_session",
              "DELETE FROM refresh_token",
              "DELETE FROM app_user",
              "DELETE FROM workspace_member",
              "DELETE FROM financial_institution",
              "DELETE FROM workspace")) {
        statement.execute(sql);
      }
    }
  }

  @Test
  void healthAndInfoAreReachableByEveryone() {
    String adminToken = bootstrapAdministrator();
    String memberToken = createStandardUser(adminToken);

    for (RestTestClient caller :
        List.of(anonymousClient(), client(memberToken), client(adminToken))) {
      for (String path :
          List.of("/actuator/health", "/actuator/health/readiness", "/actuator/info")) {
        caller.get().uri(path).exchange().expectStatus().isOk();
      }
    }
  }

  @Test
  void healthDetailsAreForAdministratorsOnly() {
    String adminToken = bootstrapAdministrator();
    String memberToken = createStandardUser(adminToken);

    // Database, disk space and its path, certificates: operational data, not a user's.
    assertThat(health(anonymousClient())).doesNotContain("components", "diskSpace");
    assertThat(health(client(memberToken))).doesNotContain("components", "diskSpace");
    assertThat(health(client(adminToken))).contains("components", "diskSpace", "db");
  }

  @Test
  void flywayMetadataIsForAdministratorsOnly() {
    String adminToken = bootstrapAdministrator();
    String memberToken = createStandardUser(adminToken);

    for (String path : List.of("/actuator/flyway", "/actuator/flyway/")) {
      anonymousClient().get().uri(path).exchange().expectStatus().isUnauthorized();
      client(memberToken).get().uri(path).exchange().expectStatus().isForbidden();
    }
    String body =
        client(adminToken)
            .get()
            .uri("/actuator/flyway")
            .exchange()
            .expectStatus()
            .isOk()
            .expectBody(String.class)
            .returnResult()
            .getResponseBody();
    assertThat(body).contains("contexts");
  }

  @Test
  void theEndpointIndexIsForAdministratorsOnly() {
    String adminToken = bootstrapAdministrator();
    String memberToken = createStandardUser(adminToken);

    // It lists every exposed endpoint - and any a deployment adds later is admin-only too.
    anonymousClient().get().uri("/actuator").exchange().expectStatus().isUnauthorized();
    client(memberToken).get().uri("/actuator").exchange().expectStatus().isForbidden();
    client(adminToken).get().uri("/actuator").exchange().expectStatus().isOk();
  }

  private String health(RestTestClient caller) {
    return caller
        .get()
        .uri("/actuator/health")
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody(String.class)
        .returnResult()
        .getResponseBody();
  }

  private String bootstrapAdministrator() {
    return anonymousClient()
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

  // A standard user created by the administrator, signed in through the real login endpoint.
  private String createStandardUser(String adminToken) {
    client(adminToken)
        .post()
        .uri("/api/v1/admin/users")
        .contentType(MediaType.APPLICATION_JSON)
        .body(new CreateUserRequest(MEMBER, PASSWORD, "STANDARD_USER", "EN"))
        .exchange()
        .expectStatus()
        .isCreated();
    return anonymousClient()
        .post()
        .uri("/api/v1/auth/login")
        .contentType(MediaType.APPLICATION_JSON)
        .body(new LoginRequest(MEMBER, PASSWORD))
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody(LoginResponse.class)
        .returnResult()
        .getResponseBody()
        .tokens()
        .accessToken();
  }

  private RestTestClient anonymousClient() {
    return RestTestClient.bindToServer().baseUrl("http://localhost:%d".formatted(port)).build();
  }

  private RestTestClient client(String accessToken) {
    return RestTestClient.bindToServer()
        .baseUrl("http://localhost:%d".formatted(port))
        .defaultHeader("Authorization", "Bearer " + accessToken)
        .build();
  }
}
