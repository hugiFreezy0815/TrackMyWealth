package com.trackmywealth.backend.controller;

import static org.assertj.core.api.Assertions.assertThat;

import com.trackmywealth.backend.dto.AuthTokensResponse;
import com.trackmywealth.backend.dto.CreateUserRequest;
import com.trackmywealth.backend.dto.EditUserRequest;
import com.trackmywealth.backend.dto.SetupAdministratorRequest;
import com.trackmywealth.backend.dto.UserSummaryResponse;
import com.trackmywealth.backend.entity.AdminAuditLog;
import com.trackmywealth.backend.entity.AppUser;
import com.trackmywealth.backend.repository.AdminAuditLogRepository;
import com.trackmywealth.backend.repository.AppUserRepository;
import com.trackmywealth.backend.repository.UserSessionRepository;
import com.trackmywealth.backend.service.TokenIssuanceService;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
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
 * US-02-01's Definition of Done: last-admin protection and token invalidation on disable, plus the
 * create/edit/reactivate paths and the authorization boundary (SYSTEM_ADMINISTRATOR-only,
 * distinguishing "not authenticated" from "authenticated but wrong role").
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AdminUserControllerTest {

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
    // FR-AUT-010's per-source rate limiter is on by default (application.yml) - these
    // tests exercise login/setup/revoke repeatedly within one continuous run and aren't
    // testing rate limiting itself, so it would otherwise trip spuriously.
    registry.add("app.rate-limit.enabled", () -> "false");
  }

  @LocalServerPort int port;

  @Autowired AppUserRepository appUserRepository;
  @Autowired AdminAuditLogRepository adminAuditLogRepository;
  @Autowired UserSessionRepository userSessionRepository;
  @Autowired TokenIssuanceService tokenIssuanceService;
  @Autowired PasswordEncoder passwordEncoder;
  @Autowired DataSource dataSource;

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
  void createUserSucceedsAndWritesAuditLog() {
    String adminToken = bootstrapAdministrator();
    AppUser admin = onlyAppUser();

    UserSummaryResponse created =
        adminClient(adminToken)
            .post()
            .uri("/api/v1/admin/users")
            .contentType(MediaType.APPLICATION_JSON)
            .body(
                new CreateUserRequest(
                    "bob@example.com", "another-strong-password", "STANDARD_USER", "EN"))
            .exchange()
            .expectStatus()
            .isEqualTo(HttpStatus.CREATED)
            .expectBody(UserSummaryResponse.class)
            .returnResult()
            .getResponseBody();

    assertThat(created).isNotNull();
    assertThat(created.email()).isEqualTo("bob@example.com");
    assertThat(created.status()).isEqualTo("ACTIVE");

    AppUser bob = appUserRepository.findById(created.id()).orElseThrow();
    assertThat(passwordEncoder.matches("another-strong-password", bob.getPasswordHash())).isTrue();

    List<AdminAuditLog> logs = adminAuditLogRepository.findAll();
    assertThat(logs)
        .anySatisfy(
            log -> {
              assertThat(log.getAction()).isEqualTo("USER_CREATED");
              assertThat(log.getActorUserId()).isEqualTo(admin.getId());
              assertThat(log.getTargetUserId()).isEqualTo(bob.getId());
            });
  }

  @Test
  void createdUserIsLinkedToAWorkspaceMember() throws Exception {
    // Regression test for #47: AdminUserService.createUser used to insert only the app_user row,
    // leaving workspace_member_id NULL - the account authenticated fine but could never access
    // any workspace-scoped data (WorkspaceContextTransactionExecutionListener never resolves a
    // workspace context for it, so RLS denies every workspace-scoped table by default).
    String adminToken = bootstrapAdministrator();
    AppUser bob = createStandardUser(adminToken, "bob@example.com");

    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement =
            connection.prepareStatement(
                "SELECT wm.display_name FROM app_user u "
                    + "JOIN workspace_member wm ON wm.id = u.workspace_member_id "
                    + "WHERE u.id = ?")) {
      statement.setObject(1, bob.getId());
      try (ResultSet rs = statement.executeQuery()) {
        assertThat(rs.next())
            .as(
                "bob's app_user row must have a resolvable workspace_member link - before this"
                    + " fix, workspace_member_id was NULL and this join returned nothing")
            .isTrue();
        // No display-name field exists on CreateUserRequest - same email-local-part fallback
        // SetupService uses for the bootstrap administrator's own member row.
        assertThat(rs.getString("display_name")).isEqualTo("bob");
      }
    }
  }

  @Test
  void administratorWithNoWorkspaceLinkCannotCreateAUser() throws Exception {
    // Simulates the pre-fix state #47 describes: an administrator with no workspace_member link
    // of their own. Not reachable via any current API path (every account-creation path now
    // links one), but createUser must still fail safely - a clear 409, not an NPE - if it were
    // ever to occur again.
    String adminToken = bootstrapAdministrator();
    AppUser admin = onlyAppUser();

    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement =
            connection.prepareStatement(
                "UPDATE app_user SET workspace_member_id = NULL WHERE id = ?")) {
      statement.setObject(1, admin.getId());
      statement.executeUpdate();
    }

    adminClient(adminToken)
        .post()
        .uri("/api/v1/admin/users")
        .contentType(MediaType.APPLICATION_JSON)
        .body(
            new CreateUserRequest(
                "nobody@example.com", "another-strong-password", "STANDARD_USER", "EN"))
        .exchange()
        .expectStatus()
        .isEqualTo(HttpStatus.CONFLICT);

    assertThat(appUserRepository.findAll()).hasSize(1);
  }

  @Test
  void creatingAUserWithAnAlreadyUsedEmailIsRejected() {
    String adminToken = bootstrapAdministrator();
    createStandardUser(adminToken, "bob@example.com");

    adminClient(adminToken)
        .post()
        .uri("/api/v1/admin/users")
        .contentType(MediaType.APPLICATION_JSON)
        .body(
            new CreateUserRequest(
                "BOB@example.com", "another-strong-password", "STANDARD_USER", "EN"))
        .exchange()
        .expectStatus()
        .isEqualTo(HttpStatus.CONFLICT);

    assertThat(appUserRepository.findAll()).hasSize(2);
  }

  @Test
  void editingAUserToAnAlreadyUsedEmailIsRejected() {
    String adminToken = bootstrapAdministrator();
    AppUser bob = createStandardUser(adminToken, "bob@example.com");
    createStandardUser(adminToken, "charlie@example.com");

    adminClient(adminToken)
        .patch()
        .uri("/api/v1/admin/users/" + bob.getId())
        .contentType(MediaType.APPLICATION_JSON)
        .body(new EditUserRequest("charlie@example.com", null, null))
        .exchange()
        .expectStatus()
        .isEqualTo(HttpStatus.CONFLICT);

    assertThat(appUserRepository.findById(bob.getId()).orElseThrow().getEmail())
        .isEqualTo("bob@example.com");
  }

  @Test
  void editingAUserToABlankEmailIsRejected() {
    String adminToken = bootstrapAdministrator();
    AppUser bob = createStandardUser(adminToken, "bob@example.com");

    adminClient(adminToken)
        .patch()
        .uri("/api/v1/admin/users/" + bob.getId())
        .contentType(MediaType.APPLICATION_JSON)
        .body(new EditUserRequest("", null, null))
        .exchange()
        .expectStatus()
        .isEqualTo(HttpStatus.BAD_REQUEST);

    assertThat(appUserRepository.findById(bob.getId()).orElseThrow().getEmail())
        .isEqualTo("bob@example.com");
  }

  @Test
  void editUserUpdatesFieldsAndLogsChange() {
    String adminToken = bootstrapAdministrator();
    AppUser bob = createStandardUser(adminToken, "bob@example.com");

    adminClient(adminToken)
        .patch()
        .uri("/api/v1/admin/users/" + bob.getId())
        .contentType(MediaType.APPLICATION_JSON)
        .body(new EditUserRequest(null, null, "DE"))
        .exchange()
        .expectStatus()
        .isOk();

    AppUser updated = appUserRepository.findById(bob.getId()).orElseThrow();
    assertThat(updated.getLanguage()).isEqualTo("DE");
    assertThat(adminAuditLogRepository.findAll())
        .anySatisfy(log -> assertThat(log.getAction()).isEqualTo("USER_EDITED"));
  }

  @Test
  void disablingAUserRevokesTokensAndInvalidatesAccessImmediately() {
    String adminToken = bootstrapAdministrator();
    AppUser charlie = createStandardUser(adminToken, "charlie@example.com");
    AuthTokensResponse charlieTokens =
        tokenIssuanceService.issueTokens(charlie, "test-device", null);

    // Authenticated (the token is valid), but STANDARD_USER lacks the role - 403, not 401. This
    // is the baseline that the post-disable assertion below is contrasted against.
    adminClient(charlieTokens.accessToken())
        .post()
        .uri("/api/v1/admin/users")
        .contentType(MediaType.APPLICATION_JSON)
        .body(
            new CreateUserRequest(
                "nobody@example.com", "irrelevant-password", "STANDARD_USER", "EN"))
        .exchange()
        .expectStatus()
        .isEqualTo(HttpStatus.FORBIDDEN);

    adminClient(adminToken)
        .post()
        .uri("/api/v1/admin/users/" + charlie.getId() + "/disable")
        .exchange()
        .expectStatus()
        .isOk();

    AppUser disabledCharlie = appUserRepository.findById(charlie.getId()).orElseThrow();
    assertThat(disabledCharlie.getStatus()).isEqualTo("DISABLED");
    assertThat(disabledCharlie.getTokenVersion()).isEqualTo(charlie.getTokenVersion() + 1);

    // US-02-03: user_session.status is a security-relevant signal now, not just a display field -
    // disable must not leave stale ACTIVE rows behind.
    assertThat(userSessionRepository.findAll())
        .filteredOn(session -> session.getUser().getId().equals(charlie.getId()))
        .isNotEmpty()
        .allSatisfy(session -> assertThat(session.getStatus()).isEqualTo("REVOKED"));

    // Same token as above, only now the user is disabled and token_version has moved on: no
    // longer authenticates at all - 401 (FR-AUT-005's "immediately", not "when the JWT expires").
    adminClient(charlieTokens.accessToken())
        .post()
        .uri("/api/v1/admin/users")
        .contentType(MediaType.APPLICATION_JSON)
        .body(
            new CreateUserRequest(
                "nobody@example.com", "irrelevant-password", "STANDARD_USER", "EN"))
        .exchange()
        .expectStatus()
        .isEqualTo(HttpStatus.UNAUTHORIZED);
  }

  @Test
  void reactivatingAUserResetsStatusAndLockout() {
    String adminToken = bootstrapAdministrator();
    AppUser charlie = createStandardUser(adminToken, "charlie@example.com");
    charlie.setFailedLoginCount(4);
    charlie.setLockedUntil(OffsetDateTime.now(ZoneOffset.UTC).plusMinutes(30));
    appUserRepository.save(charlie);
    adminClient(adminToken)
        .post()
        .uri("/api/v1/admin/users/" + charlie.getId() + "/disable")
        .exchange();

    adminClient(adminToken)
        .post()
        .uri("/api/v1/admin/users/" + charlie.getId() + "/reactivate")
        .exchange()
        .expectStatus()
        .isOk();

    AppUser reactivated = appUserRepository.findById(charlie.getId()).orElseThrow();
    assertThat(reactivated.getStatus()).isEqualTo("ACTIVE");
    assertThat(reactivated.getFailedLoginCount()).isZero();
    assertThat(reactivated.getLockedUntil()).isNull();
  }

  @Test
  void soleActiveAdministratorCanBeNeitherDisabledNorDemoted() {
    String adminToken = bootstrapAdministrator();
    AppUser admin = onlyAppUser();

    adminClient(adminToken)
        .post()
        .uri("/api/v1/admin/users/" + admin.getId() + "/disable")
        .exchange()
        .expectStatus()
        .isEqualTo(HttpStatus.CONFLICT);

    adminClient(adminToken)
        .patch()
        .uri("/api/v1/admin/users/" + admin.getId())
        .contentType(MediaType.APPLICATION_JSON)
        .body(new EditUserRequest(null, "STANDARD_USER", null))
        .exchange()
        .expectStatus()
        .isEqualTo(HttpStatus.CONFLICT);

    AppUser stillAdmin = appUserRepository.findById(admin.getId()).orElseThrow();
    assertThat(stillAdmin.getStatus()).isEqualTo("ACTIVE");
    assertThat(stillAdmin.getRole()).isEqualTo("SYSTEM_ADMINISTRATOR");
  }

  @Test
  void disablingOneOfTwoActiveAdministratorsIsAllowedButNotTheLastRemainingOne() {
    String adminToken = bootstrapAdministrator();
    AppUser firstAdmin = onlyAppUser();

    UserSummaryResponse secondAdminSummary =
        adminClient(adminToken)
            .post()
            .uri("/api/v1/admin/users")
            .contentType(MediaType.APPLICATION_JSON)
            .body(
                new CreateUserRequest(
                    "second-admin@example.com",
                    "another-strong-password",
                    "SYSTEM_ADMINISTRATOR",
                    "EN"))
            .exchange()
            .expectStatus()
            .isEqualTo(HttpStatus.CREATED)
            .expectBody(UserSummaryResponse.class)
            .returnResult()
            .getResponseBody();

    // Two active administrators: disabling one is fine.
    adminClient(adminToken)
        .post()
        .uri("/api/v1/admin/users/" + secondAdminSummary.id() + "/disable")
        .exchange()
        .expectStatus()
        .isOk();

    // Back down to one: disabling the sole remaining active administrator is rejected.
    adminClient(adminToken)
        .post()
        .uri("/api/v1/admin/users/" + firstAdmin.getId() + "/disable")
        .exchange()
        .expectStatus()
        .isEqualTo(HttpStatus.CONFLICT);
  }

  @Test
  void anonymousRequestToAdminEndpointIsUnauthorized() {
    RestTestClient.bindToServer()
        .baseUrl("http://localhost:%d".formatted(port))
        .build()
        .post()
        .uri("/api/v1/admin/users")
        .contentType(MediaType.APPLICATION_JSON)
        .body(
            new CreateUserRequest(
                "someone@example.com", "irrelevant-password", "STANDARD_USER", "EN"))
        .exchange()
        .expectStatus()
        .isEqualTo(HttpStatus.UNAUTHORIZED);
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

  private AppUser createStandardUser(String adminToken, String email) {
    UserSummaryResponse created =
        adminClient(adminToken)
            .post()
            .uri("/api/v1/admin/users")
            .contentType(MediaType.APPLICATION_JSON)
            .body(new CreateUserRequest(email, "another-strong-password", "STANDARD_USER", "EN"))
            .exchange()
            .expectStatus()
            .isEqualTo(HttpStatus.CREATED)
            .expectBody(UserSummaryResponse.class)
            .returnResult()
            .getResponseBody();
    return appUserRepository.findById(created.id()).orElseThrow();
  }

  private AppUser onlyAppUser() {
    List<AppUser> users = appUserRepository.findAll();
    assertThat(users).hasSize(1);
    return users.get(0);
  }

  private RestTestClient adminClient(String accessToken) {
    return RestTestClient.bindToServer()
        .baseUrl("http://localhost:%d".formatted(port))
        .defaultHeader("Authorization", "Bearer " + accessToken)
        .build();
  }
}
