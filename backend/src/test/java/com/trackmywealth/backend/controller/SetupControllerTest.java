package com.trackmywealth.backend.controller;

import static org.assertj.core.api.Assertions.assertThat;

import com.trackmywealth.backend.dto.SetupAdministratorRequest;
import com.trackmywealth.backend.entity.AppUser;
import com.trackmywealth.backend.entity.FinancialInstitution;
import com.trackmywealth.backend.entity.Workspace;
import com.trackmywealth.backend.entity.WorkspaceMember;
import com.trackmywealth.backend.repository.AppUserRepository;
import com.trackmywealth.backend.repository.FinancialInstitutionRepository;
import com.trackmywealth.backend.repository.WorkspaceMemberRepository;
import com.trackmywealth.backend.repository.WorkspaceRepository;
import java.sql.Connection;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.client.RestTestClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * US-01-03's Definition of Done: a fresh database can bootstrap exactly one administrator, a second
 * attempt is rejected, and the Personal Assets container exists with the requested currency - plus
 * the story's explicit concurrency edge case (two simultaneous attempts must still only ever
 * produce one administrator).
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SetupControllerTest {

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
  }

  @LocalServerPort int port;

  @Autowired WorkspaceRepository workspaceRepository;
  @Autowired WorkspaceMemberRepository workspaceMemberRepository;
  @Autowired FinancialInstitutionRepository financialInstitutionRepository;
  @Autowired AppUserRepository appUserRepository;
  @Autowired PasswordEncoder passwordEncoder;
  @Autowired DataSource dataSource;

  // Tests in this class share one Testcontainers instance and Spring context (per-class startup
  // cost is real - a fresh Postgres per test would make this suite slow); each test needs a clean
  // slate for US-01-03's "no app_user exists yet" precondition, so truncate explicitly rather than
  // relying on test execution order. The default Testcontainers Postgres user is a superuser, so
  // this bypasses RLS regardless of app.current_workspace_id - appropriate for test cleanup, never
  // application code.
  @BeforeEach
  void cleanDatabase() throws Exception {
    try (Connection connection = dataSource.getConnection();
        Statement statement = connection.createStatement()) {
      statement.execute(
          "TRUNCATE TABLE user_session, refresh_token, app_user, workspace_member,"
              + " financial_institution, workspace RESTART IDENTITY CASCADE");
    }
  }

  @Test
  void firstAttemptSucceedsAndSecondIsRejected() {
    SetupAdministratorRequest request =
        new SetupAdministratorRequest(
            "admin@example.com", "correct-horse-battery-staple", "The Example Workspace", "CHF");

    client()
        .post()
        .uri("/api/v1/setup/administrator")
        .contentType(MediaType.APPLICATION_JSON)
        .body(request)
        .exchange()
        .expectStatus()
        .isEqualTo(HttpStatus.CREATED)
        .expectBody()
        .jsonPath("$.accessToken")
        .exists()
        .jsonPath("$.refreshToken")
        .exists()
        .jsonPath("$.tokenType")
        .isEqualTo("Bearer");

    List<AppUser> users = appUserRepository.findAll();
    assertThat(users).hasSize(1);
    AppUser administrator = users.get(0);
    assertThat(administrator.getEmail()).isEqualTo("admin@example.com");
    assertThat(administrator.getRole()).isEqualTo("SYSTEM_ADMINISTRATOR");
    assertThat(administrator.getReportingCurrency()).isEqualTo("CHF");
    assertThat(
            passwordEncoder.matches(
                "correct-horse-battery-staple", administrator.getPasswordHash()))
        .isTrue();

    // administrator is detached (returned by a repository call whose own transaction has already
    // closed), so its lazy associations are uninitialized proxies - .getId() is safe without a
    // session (Hibernate resolves it from the owning row's own FK column), but navigating further
    // (.getWorkspace(), .getName()) is not, hence the fresh, independent repository lookups below
    // rather than walking the association chain directly.
    UUID memberId = administrator.getWorkspaceMember().getId();
    WorkspaceMember member = workspaceMemberRepository.findById(memberId).orElseThrow();
    UUID workspaceId = member.getWorkspace().getId();
    Workspace workspace = workspaceRepository.findById(workspaceId).orElseThrow();
    assertThat(workspace.getName()).isEqualTo("The Example Workspace");

    FinancialInstitution personalAssets =
        financialInstitutionRepository
            .findByWorkspaceIdAndPersonalAssetsDefaultTrue(workspace.getId())
            .orElseThrow();
    assertThat(personalAssets.getContainerCurrency()).isEqualTo("CHF");
    assertThat(personalAssets.getInstitutionType()).isEqualTo("PERSONAL_ASSETS");

    // Second attempt: setup is a one-time bootstrap, not a general "create admin" endpoint.
    SetupAdministratorRequest secondRequest =
        new SetupAdministratorRequest(
            "second-admin@example.com", "another-correct-battery", "A Second Workspace", "EUR");

    client()
        .post()
        .uri("/api/v1/setup/administrator")
        .contentType(MediaType.APPLICATION_JSON)
        .body(secondRequest)
        .exchange()
        .expectStatus()
        .isEqualTo(HttpStatus.CONFLICT);

    assertThat(appUserRepository.count()).isEqualTo(1);
  }

  @Test
  void concurrentSetupAttemptsOnlyEverProduceOneAdministrator() throws Exception {
    SetupAdministratorRequest request =
        new SetupAdministratorRequest(
            "racer@example.com", "does-not-matter-which-wins", "Race Workspace", "CHF");

    Callable<Integer> attempt =
        () ->
            client()
                .post()
                .uri("/api/v1/setup/administrator")
                .contentType(MediaType.APPLICATION_JSON)
                .body(request)
                .exchange()
                .returnResult()
                .getStatus()
                .value();

    ExecutorService executor = Executors.newFixedThreadPool(2);
    try {
      Future<Integer> first = executor.submit(attempt);
      Future<Integer> second = executor.submit(attempt);
      List<Integer> statusCodes =
          List.of(first.get(30, TimeUnit.SECONDS), second.get(30, TimeUnit.SECONDS));

      assertThat(statusCodes)
          .containsExactlyInAnyOrder(HttpStatus.CREATED.value(), HttpStatus.CONFLICT.value());
      assertThat(appUserRepository.count()).isEqualTo(1);
    } finally {
      executor.shutdownNow();
    }
  }

  private RestTestClient client() {
    return RestTestClient.bindToServer().baseUrl("http://localhost:%d".formatted(port)).build();
  }
}
