package com.trackmywealth.backend.controller;

import static org.assertj.core.api.Assertions.assertThat;

import com.trackmywealth.backend.dto.AuthTokensResponse;
import com.trackmywealth.backend.dto.LoginRequest;
import com.trackmywealth.backend.dto.LoginResponse;
import com.trackmywealth.backend.dto.MfaConfirmRequest;
import com.trackmywealth.backend.dto.MfaEnrollmentRequest;
import com.trackmywealth.backend.dto.MfaEnrollmentResponse;
import com.trackmywealth.backend.dto.MfaVerifyRequest;
import com.trackmywealth.backend.dto.RefreshTokenRequest;
import com.trackmywealth.backend.dto.SetupAdministratorRequest;
import com.trackmywealth.backend.dto.WorkspaceResponse;
import com.trackmywealth.backend.testsupport.RoleSplitScripts;
import dev.samstevens.totp.code.DefaultCodeGenerator;
import java.io.IOException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
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
 * #321 and US-28-05 (#331) end to end, on the shipped role split: Flyway runs as the migration
 * role, which owns the schema but is no superuser, and every HTTP request - the JWT filter, the
 * workspace context listener, the services and repositories - runs as the {@code NOSUPERUSER
 * NOBYPASSRLS} runtime role. An active member reaches their own workspace and nothing of another;
 * once the member is INACTIVE, the access token, login, MFA verification and refresh are each
 * refused, as under the superuser.
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class HardenedRuntimeAuthenticationHttpTest {

  private static final String SUPERUSER = "trackmywealth";
  private static final String SUPERUSER_PASSWORD = "trackmywealth";
  private static final String MIGRATION_PASSWORD = "migration-integration-password";
  private static final String RUNTIME_PASSWORD = "runtime-integration-password";
  private static final String EMAIL = "hardened@example.invalid";
  private static final String PASSWORD = "correct-horse-battery-staple";
  private static final int TOTP_PERIOD_SECONDS = 30;

  @Container
  static PostgreSQLContainer<?> postgres =
      new PostgreSQLContainer<>("postgres:16")
          .withDatabaseName("trackmywealth")
          .withUsername(SUPERUSER)
          .withPassword(SUPERUSER_PASSWORD);

  private final DefaultCodeGenerator codeGenerator = new DefaultCodeGenerator();

  // US-28-05 (#331): the shipped role split (deploy/postgres) provisions both roles before Flyway
  // runs, as on a fresh compose volume. The migration role is no superuser, so the application's
  // start also proves that every migration runs under it.
  @BeforeAll
  static void splitRoles() throws IOException, SQLException {
    RoleSplitScripts.apply(
        postgres.getJdbcUrl(),
        SUPERUSER,
        SUPERUSER_PASSWORD,
        postgres.getDatabaseName(),
        MIGRATION_PASSWORD,
        RUNTIME_PASSWORD);
  }

  @DynamicPropertySource
  static void datasource(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", postgres::getJdbcUrl);
    registry.add("spring.datasource.username", () -> RoleSplitScripts.RUNTIME_ROLE);
    registry.add("spring.datasource.password", () -> RUNTIME_PASSWORD);
    // The application's own switch (DatabaseRoleConfig), not spring.flyway.user.
    registry.add("app.database.migration-username", () -> RoleSplitScripts.MIGRATION_ROLE);
    registry.add("app.database.migration-password", () -> MIGRATION_PASSWORD);
    // The context only starts if the runtime role is bound by row-level security.
    registry.add("app.database.require-rls-bound-role", () -> "true");
    registry.add("app.database.auto-create", () -> "false");
    registry.add("app.rate-limit.enabled", () -> "false");
  }

  @LocalServerPort int port;

  @Test
  void anActiveMemberReachesOnlyTheirWorkspaceAndAnInactiveOneIsRefusedEverywhere()
      throws Exception {
    assertThat(runtimeBypassesRls())
        .as("the application connects as a role bound by RLS")
        .isFalse();
    AuthTokensResponse setup =
        client()
            .post()
            .uri("/api/v1/setup/administrator")
            .contentType(MediaType.APPLICATION_JSON)
            .body(new SetupAdministratorRequest(EMAIL, PASSWORD, "My Workspace", "CHF"))
            .exchange()
            .expectStatus()
            .isEqualTo(HttpStatus.CREATED)
            .expectBody(AuthTokensResponse.class)
            .returnResult()
            .getResponseBody();

    // The principal carries the member's workspace, so the workspace context reaches RLS.
    WorkspaceResponse workspace =
        authorized(setup.accessToken())
            .get()
            .uri("/api/v1/workspace")
            .exchange()
            .expectStatus()
            .isOk()
            .expectBody(WorkspaceResponse.class)
            .returnResult()
            .getResponseBody();
    assertThat(workspace.name()).isEqualTo("My Workspace");
    // ... and only that workspace: another workspace's account is the generic 404.
    UUID foreignAccount = insertAccountInAnotherWorkspace();
    authorized(setup.accessToken())
        .get()
        .uri("/api/v1/accounts/" + foreignAccount)
        .exchange()
        .expectStatus()
        .isNotFound();

    // MFA on: login hands out a challenge, which the eligibility check guards like login itself.
    String secret = enrollMfa(setup.accessToken());
    AuthTokensResponse verified =
        verifyMfa(login().mfaChallengeToken(), codeAtStep(secret, 0))
            .expectStatus()
            .isOk()
            .expectBody(AuthTokensResponse.class)
            .returnResult()
            .getResponseBody();
    String pendingChallenge = login().mfaChallengeToken();

    setMemberStatus("INACTIVE");

    authorized(setup.accessToken())
        .get()
        .uri("/api/v1/workspace")
        .exchange()
        .expectStatus()
        .isUnauthorized();
    loginRequest().expectStatus().isUnauthorized();
    // A fresh, never used step: only the member's status can refuse it.
    verifyMfa(pendingChallenge, codeAtStep(secret, 1)).expectStatus().isUnauthorized();
    client()
        .post()
        .uri("/api/v1/auth/refresh")
        .contentType(MediaType.APPLICATION_JSON)
        .body(new RefreshTokenRequest(verified.refreshToken()))
        .exchange()
        .expectStatus()
        .isUnauthorized();
  }

  // Enrolls and confirms TOTP; the confirmation uses the step before the ones verified later, so
  // no code in this test is refused as a replay.
  private String enrollMfa(String accessToken) throws Exception {
    MfaEnrollmentResponse enrollment =
        authorized(accessToken)
            .post()
            .uri("/api/v1/users/me/mfa/enroll")
            .contentType(MediaType.APPLICATION_JSON)
            .body(new MfaEnrollmentRequest(PASSWORD))
            .exchange()
            .expectStatus()
            .isOk()
            .expectBody(MfaEnrollmentResponse.class)
            .returnResult()
            .getResponseBody();
    authorized(accessToken)
        .post()
        .uri("/api/v1/users/me/mfa/confirm")
        .contentType(MediaType.APPLICATION_JSON)
        .body(new MfaConfirmRequest(codeAtStep(enrollment.secret(), -1)))
        .exchange()
        .expectStatus()
        .isNoContent();
    return enrollment.secret();
  }

  private LoginResponse login() {
    LoginResponse response =
        loginRequest()
            .expectStatus()
            .isOk()
            .expectBody(LoginResponse.class)
            .returnResult()
            .getResponseBody();
    assertThat(response.mfaRequired()).isTrue();
    return response;
  }

  private RestTestClient.ResponseSpec loginRequest() {
    return client()
        .post()
        .uri("/api/v1/auth/login")
        .contentType(MediaType.APPLICATION_JSON)
        .body(new LoginRequest(EMAIL, PASSWORD))
        .exchange();
  }

  private RestTestClient.ResponseSpec verifyMfa(String challenge, String code) {
    return client()
        .post()
        .uri(AuthController.MFA_VERIFY_PATH)
        .contentType(MediaType.APPLICATION_JSON)
        .body(new MfaVerifyRequest(challenge, code))
        .exchange();
  }

  // The code of the current TOTP step plus offset; the server accepts one step either side.
  private String codeAtStep(String secret, int offset) throws Exception {
    long step = Math.floorDiv(Instant.now().getEpochSecond(), TOTP_PERIOD_SECONDS);
    return codeGenerator.generate(secret, step + offset);
  }

  // Whether the application's own connection is a superuser, which would bypass every policy and
  // let this test pass for the wrong reason.
  private static boolean runtimeBypassesRls() throws SQLException {
    try (Connection runtime =
            DriverManager.getConnection(
                postgres.getJdbcUrl(), RoleSplitScripts.RUNTIME_ROLE, RUNTIME_PASSWORD);
        Statement statement = runtime.createStatement();
        ResultSet result =
            statement.executeQuery(
                "SELECT rolsuper OR rolbypassrls FROM pg_roles WHERE rolname = current_user")) {
      assertThat(result.next()).isTrue();
      return result.getBoolean(1);
    }
  }

  // Written as the superuser, outside the application: a second workspace (V19's trigger gives it
  // its
  // personal-assets container) with one account.
  private static UUID insertAccountInAnotherWorkspace() throws SQLException {
    UUID workspace = UUID.randomUUID();
    UUID account = UUID.randomUUID();
    try (Connection superuser = superuserConnection()) {
      try (PreparedStatement insert =
          superuser.prepareStatement("INSERT INTO workspace (id, name) VALUES (?, 'Other')")) {
        insert.setObject(1, workspace);
        insert.executeUpdate();
      }
      try (PreparedStatement insert =
          superuser.prepareStatement(
              "INSERT INTO account (id, workspace_id, financial_institution_id, account_type,"
                  + " name, native_currency) SELECT ?, ?, id, 'CASH', 'Foreign', 'CHF'"
                  + " FROM financial_institution"
                  + " WHERE workspace_id = ? AND is_personal_assets_default")) {
        insert.setObject(1, account);
        insert.setObject(2, workspace);
        insert.setObject(3, workspace);
        assertThat(insert.executeUpdate()).isOne();
      }
    }
    return account;
  }

  // Changed as the superuser, outside the application's authorization path; the requests that
  // follow
  // still run as the runtime role.
  private static void setMemberStatus(String status) throws SQLException {
    try (Connection superuser = superuserConnection();
        PreparedStatement update =
            superuser.prepareStatement(
                "UPDATE workspace_member SET status = ? WHERE id ="
                    + " (SELECT workspace_member_id FROM app_user WHERE email = ?)")) {
      update.setString(1, status);
      update.setString(2, EMAIL);
      assertThat(update.executeUpdate()).isOne();
    }
  }

  private static Connection superuserConnection() throws SQLException {
    return DriverManager.getConnection(postgres.getJdbcUrl(), SUPERUSER, SUPERUSER_PASSWORD);
  }

  private RestTestClient authorized(String accessToken) {
    return RestTestClient.bindToServer()
        .baseUrl("http://localhost:%d".formatted(port))
        .defaultHeader("Authorization", "Bearer " + accessToken)
        .build();
  }

  private RestTestClient client() {
    return RestTestClient.bindToServer().baseUrl("http://localhost:%d".formatted(port)).build();
  }
}
