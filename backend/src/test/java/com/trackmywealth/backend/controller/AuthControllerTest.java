package com.trackmywealth.backend.controller;

import static org.assertj.core.api.Assertions.assertThat;

import com.trackmywealth.backend.dto.AuthTokensResponse;
import com.trackmywealth.backend.dto.CreateUserRequest;
import com.trackmywealth.backend.dto.LoginRequest;
import com.trackmywealth.backend.dto.LoginResponse;
import com.trackmywealth.backend.dto.RefreshTokenRequest;
import com.trackmywealth.backend.dto.SetupAdministratorRequest;
import com.trackmywealth.backend.dto.UserSummaryResponse;
import com.trackmywealth.backend.entity.AppUser;
import com.trackmywealth.backend.entity.RefreshToken;
import com.trackmywealth.backend.repository.AppUserRepository;
import com.trackmywealth.backend.repository.RefreshTokenRepository;
import com.trackmywealth.backend.service.TokenHashingService;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.client.RestTestClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * US-02-02's Definition of Done: login issuing tokens, lockout after repeated failures, and the
 * refresh rotation + reuse (theft) detection path.
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AuthControllerTest {

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
    // FR-AUT-010's per-source rate limiter is on by default (application.yml) - these
    // tests exercise login/setup/revoke repeatedly within one continuous run and aren't
    // testing rate limiting itself, so it would otherwise trip spuriously.
    registry.add("app.rate-limit.enabled", () -> "false");
  }

  @LocalServerPort int port;

  @Autowired AppUserRepository appUserRepository;
  @Autowired RefreshTokenRepository refreshTokenRepository;
  @Autowired TokenHashingService tokenHashingService;
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
  void loginWithCorrectCredentialsIssuesTokensAndResetsFailureState() {
    bootstrapAdministrator();

    LoginResponse response =
        client()
            .post()
            .uri("/api/v1/auth/login")
            .contentType(MediaType.APPLICATION_JSON)
            .body(new LoginRequest("admin@example.com", PASSWORD))
            .exchange()
            .expectStatus()
            .isOk()
            .expectBody(LoginResponse.class)
            .returnResult()
            .getResponseBody();

    assertThat(response).isNotNull();
    assertThat(response.mfaRequired()).isFalse();
    assertThat(response.tokens()).isNotNull();
    assertThat(response.tokens().accessToken()).isNotBlank();
    assertThat(response.tokens().refreshToken()).isNotBlank();

    AppUser admin = onlyAppUser();
    assertThat(admin.getFailedLoginCount()).isZero();
    assertThat(admin.getLockedUntil()).isNull();
    assertThat(admin.getLastLoginAt()).isNotNull();
  }

  @Test
  void loginWithWrongPasswordIsRejectedAndCountsTheFailure() {
    bootstrapAdministrator();

    client()
        .post()
        .uri("/api/v1/auth/login")
        .contentType(MediaType.APPLICATION_JSON)
        .body(new LoginRequest("admin@example.com", "wrong-password"))
        .exchange()
        .expectStatus()
        .isEqualTo(HttpStatus.UNAUTHORIZED);

    assertThat(onlyAppUser().getFailedLoginCount()).isEqualTo(1);
  }

  @Test
  void loginWithUnknownEmailIsRejectedGenerically() {
    client()
        .post()
        .uri("/api/v1/auth/login")
        .contentType(MediaType.APPLICATION_JSON)
        .body(new LoginRequest("nobody@example.com", "irrelevant-password"))
        .exchange()
        .expectStatus()
        .isEqualTo(HttpStatus.UNAUTHORIZED);
  }

  @Test
  void fifthConsecutiveFailureLocksTheAccountAndEvenTheCorrectPasswordIsThenRejected() {
    bootstrapAdministrator();

    for (int attempt = 1; attempt <= 5; attempt++) {
      client()
          .post()
          .uri("/api/v1/auth/login")
          .contentType(MediaType.APPLICATION_JSON)
          .body(new LoginRequest("admin@example.com", "wrong-password"))
          .exchange()
          .expectStatus()
          .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    AppUser lockedAdmin = onlyAppUser();
    assertThat(lockedAdmin.getFailedLoginCount()).isEqualTo(5);
    assertThat(lockedAdmin.getLockedUntil()).isAfter(OffsetDateTime.now(ZoneOffset.UTC));

    // Correct password, but the account is locked - rejected without even resetting the counter.
    client()
        .post()
        .uri("/api/v1/auth/login")
        .contentType(MediaType.APPLICATION_JSON)
        .body(new LoginRequest("admin@example.com", PASSWORD))
        .exchange()
        .expectStatus()
        .isEqualTo(HttpStatus.LOCKED);
  }

  @Test
  void loginOnADisabledAccountIsRejectedGenericallyEvenWithTheCorrectPassword() {
    String adminToken = bootstrapAdministrator();
    createStandardUser(adminToken, "charlie@example.com");
    AppUser charlie = onlyStandardUser();

    client()
        .post()
        .uri("/api/v1/admin/users/" + charlie.getId() + "/disable")
        .header("Authorization", "Bearer " + adminToken)
        .exchange()
        .expectStatus()
        .isOk();

    // Generic 401, the same as a wrong password or an unknown email - not a distinguishable 403 -
    // so a disabled account's status is never confirmable from the response to a login attempt,
    // even one using the account's real, correct password.
    client()
        .post()
        .uri("/api/v1/auth/login")
        .contentType(MediaType.APPLICATION_JSON)
        .body(new LoginRequest("charlie@example.com", "another-strong-password"))
        .exchange()
        .expectStatus()
        .isEqualTo(HttpStatus.UNAUTHORIZED);
  }

  @Test
  void reactivatingAUserDropsItsStaleRevokedTokensSoAReplayIsNotMisflaggedAsTheft() {
    String adminToken = bootstrapAdministrator();
    createStandardUser(adminToken, "charlie@example.com");
    AuthTokensResponse charlieTokens = login("charlie@example.com", "another-strong-password");
    AppUser charlie = onlyStandardUser();

    client()
        .post()
        .uri("/api/v1/admin/users/" + charlie.getId() + "/disable")
        .header("Authorization", "Bearer " + adminToken)
        .exchange()
        .expectStatus()
        .isOk();
    client()
        .post()
        .uri("/api/v1/admin/users/" + charlie.getId() + "/reactivate")
        .header("Authorization", "Bearer " + adminToken)
        .exchange()
        .expectStatus()
        .isOk();

    // The client's old refresh token (revoked by disable) is gone entirely, not merely revoked -
    // a plain "invalid token" 401, never the theft-flagged family-wide response reuse gets.
    refresh(charlieTokens.refreshToken()).expectStatus().isEqualTo(HttpStatus.UNAUTHORIZED);
    assertThat(refreshTokenRepository.findAll())
        .noneMatch(token -> token.getUser().getId().equals(charlie.getId()));
  }

  @Test
  void loginForAnMfaEnabledUserReturnsAChallengeInsteadOfTokens() throws Exception {
    bootstrapAdministrator();
    AppUser admin = onlyAppUser();
    enableMfaDirectlyInDatabase(admin.getId());

    LoginResponse response =
        client()
            .post()
            .uri("/api/v1/auth/login")
            .contentType(MediaType.APPLICATION_JSON)
            .body(new LoginRequest("admin@example.com", PASSWORD))
            .exchange()
            .expectStatus()
            .isOk()
            .expectBody(LoginResponse.class)
            .returnResult()
            .getResponseBody();

    assertThat(response).isNotNull();
    assertThat(response.mfaRequired()).isTrue();
    assertThat(response.tokens()).isNull();
    assertThat(response.mfaChallengeToken()).isNotBlank();
  }

  @Test
  void refreshRotatesTheTokenAndReuseOfTheOldOneInvalidatesTheWholeFamily() {
    // bootstrapAdministrator() itself auto-logs-in (US-01-03), which starts its own,
    // never-touched-again token family - only the family started by the explicit login() call
    // below is exercised by this test, so assertions must be scoped to that family, not to every
    // refresh_token row in the database.
    bootstrapAdministrator();
    AuthTokensResponse initial = login("admin@example.com", PASSWORD);
    UUID familyId = familyIdOf(initial.refreshToken());

    AuthTokensResponse rotated =
        refresh(initial.refreshToken())
            .expectStatus()
            .isOk()
            .expectBody(AuthTokensResponse.class)
            .returnResult()
            .getResponseBody();
    assertThat(rotated).isNotNull();
    assertThat(rotated.refreshToken()).isNotEqualTo(initial.refreshToken());
    assertThat(rotated.accessToken()).isNotBlank();

    // The new token works.
    refresh(rotated.refreshToken()).expectStatus().isOk();

    // Reusing the very first (already rotated-away) token is theft: rejected, and it takes down
    // every token in the family - including the one issued by the rotation directly above.
    refresh(initial.refreshToken()).expectStatus().isEqualTo(HttpStatus.UNAUTHORIZED);

    assertThat(tokensInFamily(familyId))
        .hasSize(3)
        .allSatisfy(token -> assertThat(token.isTheftSuspected()).isTrue());
  }

  @Test
  void refreshWithAnUnknownTokenIsRejected() {
    refresh("not-a-real-refresh-token").expectStatus().isEqualTo(HttpStatus.UNAUTHORIZED);
  }

  @Test
  void refreshWithNoBackingSessionIsRejectedRatherThanIssuingAnUnrevocableAccessToken()
      throws Exception {
    bootstrapAdministrator();
    AuthTokensResponse initial = login("admin@example.com", PASSWORD);

    // Every access token is bound to a session id (US-02-03) - a refresh token whose backing
    // user_session row is gone must not silently succeed anyway.
    deleteUserSessionsDirectlyInDatabase();

    refresh(initial.refreshToken()).expectStatus().isEqualTo(HttpStatus.UNAUTHORIZED);
  }

  private void deleteUserSessionsDirectlyInDatabase() throws Exception {
    try (Connection connection = dataSource.getConnection();
        Statement statement = connection.createStatement()) {
      statement.execute("DELETE FROM user_session");
    }
  }

  @Test
  void concurrentRefreshOfTheSameTokenOnlyEverLetsOneWinAndStillKillsTheWholeFamily()
      throws Exception {
    bootstrapAdministrator();
    AuthTokensResponse initial = login("admin@example.com", PASSWORD);
    UUID familyId = familyIdOf(initial.refreshToken());

    Callable<Integer> attempt =
        () -> refresh(initial.refreshToken()).returnResult().getStatus().value();

    ExecutorService executor = Executors.newFixedThreadPool(2);
    try {
      Future<Integer> first = executor.submit(attempt);
      Future<Integer> second = executor.submit(attempt);
      List<Integer> statusCodes =
          List.of(first.get(30, TimeUnit.SECONDS), second.get(30, TimeUnit.SECONDS));

      // Exactly one request wins the race; the loser is rejected as reuse - never two successes,
      // which would mean the family silently forked (RefreshTokenRepository's atomic
      // markRotatedOutByIfStillActive is what makes this deterministic rather than a coin flip
      // dependent on statement timing).
      assertThat(statusCodes)
          .containsExactlyInAnyOrder(HttpStatus.OK.value(), HttpStatus.UNAUTHORIZED.value());

      // Whichever one won, the whole family - including the token it just issued - is
      // theft-suspected: a race on a valid token's single use is indistinguishable from an actual
      // reuse attempt, so the safe response is the same either way.
      assertThat(tokensInFamily(familyId))
          .allSatisfy(token -> assertThat(token.isTheftSuspected()).isTrue());
    } finally {
      executor.shutdownNow();
    }
  }

  @Test
  void refreshWithAnExpiredTokenIsRejectedWithoutTriggeringTheftDetection() throws Exception {
    bootstrapAdministrator();
    AuthTokensResponse initial = login("admin@example.com", PASSWORD);
    UUID familyId = familyIdOf(initial.refreshToken());

    expireTheOnlyRefreshTokenDirectlyInDatabase();

    refresh(initial.refreshToken()).expectStatus().isEqualTo(HttpStatus.UNAUTHORIZED);
    assertThat(tokensInFamily(familyId))
        .hasSize(1)
        .allSatisfy(token -> assertThat(token.isTheftSuspected()).isFalse());
  }

  private UUID familyIdOf(String plaintextRefreshToken) {
    return refreshTokenRepository
        .findByTokenHash(tokenHashingService.sha256Hex(plaintextRefreshToken))
        .orElseThrow()
        .getFamilyId();
  }

  private List<RefreshToken> tokensInFamily(UUID familyId) {
    return refreshTokenRepository.findAll().stream()
        .filter(token -> token.getFamilyId().equals(familyId))
        .toList();
  }

  private RestTestClient.ResponseSpec refresh(String refreshToken) {
    return client()
        .post()
        .uri("/api/v1/auth/refresh")
        .contentType(MediaType.APPLICATION_JSON)
        .body(new RefreshTokenRequest(refreshToken))
        .exchange();
  }

  private AuthTokensResponse login(String email, String password) {
    LoginResponse response =
        client()
            .post()
            .uri("/api/v1/auth/login")
            .contentType(MediaType.APPLICATION_JSON)
            .body(new LoginRequest(email, password))
            .exchange()
            .expectStatus()
            .isOk()
            .expectBody(LoginResponse.class)
            .returnResult()
            .getResponseBody();
    return response.tokens();
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
        .body(new CreateUserRequest(email, "another-strong-password", "STANDARD_USER", "EN"))
        .exchange()
        .expectStatus()
        .isEqualTo(HttpStatus.CREATED)
        .expectBody(UserSummaryResponse.class);
  }

  private AppUser onlyAppUser() {
    return appUserRepository.findByEmail("admin@example.com").orElseThrow();
  }

  private AppUser onlyStandardUser() {
    return appUserRepository.findByEmail("charlie@example.com").orElseThrow();
  }

  private void enableMfaDirectlyInDatabase(UUID userId) throws Exception {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement =
            connection.prepareStatement("UPDATE app_user SET mfa_enabled = true WHERE id = ?")) {
      statement.setObject(1, userId);
      statement.executeUpdate();
    }
  }

  private void expireTheOnlyRefreshTokenDirectlyInDatabase() throws Exception {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement =
            connection.prepareStatement("UPDATE refresh_token SET expires_at = ?")) {
      statement.setObject(1, OffsetDateTime.now(ZoneOffset.UTC).minusDays(1));
      statement.executeUpdate();
    }
  }

  private RestTestClient client() {
    return RestTestClient.bindToServer().baseUrl("http://localhost:%d".formatted(port)).build();
  }
}
