package com.trackmywealth.backend.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.trackmywealth.backend.dto.AuthTokensResponse;
import com.trackmywealth.backend.dto.CreateUserRequest;
import com.trackmywealth.backend.dto.SetupAdministratorRequest;
import com.trackmywealth.backend.dto.UserSummaryResponse;
import com.trackmywealth.backend.entity.AppUser;
import com.trackmywealth.backend.repository.AppUserRepository;
import com.trackmywealth.backend.service.TokenIssuanceService;
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
 * Security contract for Actuator endpoints (#191): deployment probes stay public, while Flyway's
 * schema/migration metadata is an operational surface restricted to system administrators.
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ActuatorSecurityIntegrationTest {

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
  @Autowired AppUserRepository appUserRepository;
  @Autowired TokenIssuanceService tokenIssuanceService;

  @BeforeEach
  void cleanDatabase() throws Exception {
    try (Connection connection = dataSource.getConnection();
        Statement statement = connection.createStatement()) {
      statement.execute(
          "TRUNCATE TABLE admin_audit_log, user_session, refresh_token, app_user,"
              + " workspace_member, financial_institution, workspace RESTART IDENTITY CASCADE");
    }
  }

  @Test
  void healthAndInfoRemainPublic() {
    client().get().uri("/actuator/health").exchange().expectStatus().isOk();
    client().get().uri("/actuator/info").exchange().expectStatus().isOk();
  }

  @Test
  void anonymousCallerCannotReadFlywayMetadata() {
    client()
        .get()
        .uri("/actuator/flyway")
        .exchange()
        .expectStatus()
        .isEqualTo(HttpStatus.UNAUTHORIZED);
  }

  @Test
  void standardUserCannotReadFlywayMetadata() {
    String adminToken = bootstrapAdministrator();
    AppUser standardUser = createStandardUser(adminToken);
    String standardUserToken =
        tokenIssuanceService.issueTokens(standardUser, "actuator-security-test", null).accessToken();

    authenticatedClient(standardUserToken)
        .get()
        .uri("/actuator/flyway")
        .exchange()
        .expectStatus()
        .isEqualTo(HttpStatus.FORBIDDEN);
  }

  @Test
  void systemAdministratorCanReadFlywayMetadata() {
    String adminToken = bootstrapAdministrator();

    String body =
        authenticatedClient(adminToken)
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

  private String bootstrapAdministrator() {
    return client()
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

  private AppUser createStandardUser(String adminToken) {
    UserSummaryResponse created =
        authenticatedClient(adminToken)
            .post()
            .uri("/api/v1/admin/users")
            .contentType(MediaType.APPLICATION_JSON)
            .body(
                new CreateUserRequest(
                    "standard@example.com",
                    "another-strong-password",
                    "STANDARD_USER",
                    "EN"))
            .exchange()
            .expectStatus()
            .isEqualTo(HttpStatus.CREATED)
            .expectBody(UserSummaryResponse.class)
            .returnResult()
            .getResponseBody();

    assertThat(created).isNotNull();
    return appUserRepository.findById(created.id()).orElseThrow();
  }

  private RestTestClient client() {
    return RestTestClient.bindToServer().baseUrl("http://localhost:%d".formatted(port)).build();
  }

  private RestTestClient authenticatedClient(String accessToken) {
    return RestTestClient.bindToServer()
        .baseUrl("http://localhost:%d".formatted(port))
        .defaultHeader("Authorization", "Bearer " + accessToken)
        .build();
  }
}
