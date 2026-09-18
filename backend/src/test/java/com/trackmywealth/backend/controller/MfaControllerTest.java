package com.trackmywealth.backend.controller;

import static org.assertj.core.api.Assertions.assertThat;

import com.trackmywealth.backend.dto.AuthTokensResponse;
import com.trackmywealth.backend.dto.LoginRequest;
import com.trackmywealth.backend.dto.LoginResponse;
import com.trackmywealth.backend.dto.MfaConfirmRequest;
import com.trackmywealth.backend.dto.MfaDisableRequest;
import com.trackmywealth.backend.dto.MfaEnrollmentRequest;
import com.trackmywealth.backend.dto.MfaEnrollmentResponse;
import com.trackmywealth.backend.dto.MfaVerifyRequest;
import com.trackmywealth.backend.dto.SetupAdministratorRequest;
import com.trackmywealth.backend.entity.AppUser;
import com.trackmywealth.backend.repository.AppUserRepository;
import dev.samstevens.totp.code.DefaultCodeGenerator;
import java.sql.Connection;
import java.sql.Statement;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
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
 * US-02-04's Definition of Done: enrollment, login with MFA, and rejection of a stale TOTP code -
 * plus the edge cases around them (unconfirmed enrollment never gates login, re-authentication by
 * password, challenge tokens and access tokens never interchangeable, disablement).
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class MfaControllerTest {

  private static final String EMAIL = "admin@example.com";
  private static final String PASSWORD = "correct-horse-battery-staple";
  private static final int PERIOD_SECONDS = 30;

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
    // Rate limiting has its own coverage (RateLimitFilterTest); these tests hit the MFA endpoints
    // repeatedly within one run and would otherwise trip it spuriously.
    registry.add("app.rate-limit.enabled", () -> "false");
  }

  @LocalServerPort int port;

  @Autowired AppUserRepository appUserRepository;
  @Autowired DataSource dataSource;

  private final DefaultCodeGenerator codeGenerator = new DefaultCodeGenerator();

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
  void enrollmentReturnsSetupDataStoresTheSecretEncryptedAndDoesNotEnableMfaYet() {
    String accessToken = bootstrapAdministrator();

    MfaEnrollmentResponse enrollment = enroll(accessToken, PASSWORD);

    assertThat(enrollment.secret()).isNotBlank();
    assertThat(enrollment.otpauthUri())
        .startsWith("otpauth://totp/")
        .contains("secret=" + enrollment.secret())
        .contains("issuer=TrackMyWealth");

    AppUser admin = admin();
    assertThat(admin.isMfaEnabled()).isFalse();
    // Stored encrypted (NFR-SEC-001), never the plaintext base32 secret the client was just given.
    assertThat(admin.getMfaTotpSecret()).isNotBlank().doesNotContain(enrollment.secret());
  }

  @Test
  void anUnconfirmedEnrollmentDoesNotGateLogin() {
    String accessToken = bootstrapAdministrator();
    enroll(accessToken, PASSWORD);

    LoginResponse login = login();

    assertThat(login.mfaRequired()).isFalse();
    assertThat(login.tokens()).isNotNull();
    assertThat(login.mfaChallengeToken()).isNull();
  }

  @Test
  void confirmingWithAValidCodeEnablesMfa() throws Exception {
    String accessToken = bootstrapAdministrator();
    MfaEnrollmentResponse enrollment = enroll(accessToken, PASSWORD);

    confirm(accessToken, currentCode(enrollment.secret())).expectStatus().isNoContent();

    assertThat(admin().isMfaEnabled()).isTrue();
  }

  @Test
  void confirmingWithAWrongCodeIsRejectedAndLeavesMfaDisabled() throws Exception {
    String accessToken = bootstrapAdministrator();
    MfaEnrollmentResponse enrollment = enroll(accessToken, PASSWORD);

    confirm(accessToken, wrongCode(enrollment.secret()))
        .expectStatus()
        .isEqualTo(HttpStatus.UNAUTHORIZED);

    assertThat(admin().isMfaEnabled()).isFalse();
  }

  @Test
  void confirmingWithoutAnEnrollmentInProgressIsAConflict() {
    String accessToken = bootstrapAdministrator();

    confirm(accessToken, "123456").expectStatus().isEqualTo(HttpStatus.CONFLICT);
  }

  @Test
  void enrollmentRequiresTheCurrentPassword() {
    String accessToken = bootstrapAdministrator();

    client()
        .post()
        .uri("/api/v1/users/me/mfa/enroll")
        .header("Authorization", "Bearer " + accessToken)
        .contentType(MediaType.APPLICATION_JSON)
        .body(new MfaEnrollmentRequest("not-the-password"))
        .exchange()
        .expectStatus()
        .isEqualTo(HttpStatus.UNAUTHORIZED);

    assertThat(admin().getMfaTotpSecret()).isNull();
  }

  @Test
  void mfaEndpointsRequireAuthentication() {
    client()
        .post()
        .uri("/api/v1/users/me/mfa/enroll")
        .contentType(MediaType.APPLICATION_JSON)
        .body(new MfaEnrollmentRequest(PASSWORD))
        .exchange()
        .expectStatus()
        .isEqualTo(HttpStatus.UNAUTHORIZED);
  }

  @Test
  void loginForAnMfaEnabledUserRequiresASecondStepBeforeAnyTokenIsIssued() throws Exception {
    String secret = enrollAndConfirm().secret();

    LoginResponse login = login();

    assertThat(login.mfaRequired()).isTrue();
    assertThat(login.tokens()).isNull();
    assertThat(login.mfaChallengeToken()).isNotBlank();

    AuthTokensResponse tokens =
        verify(login.mfaChallengeToken(), currentCode(secret))
            .expectStatus()
            .isOk()
            .expectBody(AuthTokensResponse.class)
            .returnResult()
            .getResponseBody();

    assertThat(tokens).isNotNull();
    assertThat(tokens.accessToken()).isNotBlank();
    assertThat(tokens.refreshToken()).isNotBlank();
    // The tokens the second step issues are real: they authenticate an ordinary request.
    client()
        .get()
        .uri("/api/v1/sessions")
        .header("Authorization", "Bearer " + tokens.accessToken())
        .exchange()
        .expectStatus()
        .isOk();
  }

  @Test
  void aWrongCodeAtTheSecondStepIsRejected() throws Exception {
    String secret = enrollAndConfirm().secret();
    LoginResponse login = login();

    verify(login.mfaChallengeToken(), wrongCode(secret))
        .expectStatus()
        .isEqualTo(HttpStatus.UNAUTHORIZED);
  }

  @Test
  void aStaleCodeFromLongAgoIsRejected() throws Exception {
    String secret = enrollAndConfirm().secret();
    LoginResponse login = login();

    verify(login.mfaChallengeToken(), staleCode(secret))
        .expectStatus()
        .isEqualTo(HttpStatus.UNAUTHORIZED);
  }

  @Test
  void aCodeFromTheAdjacentTimeStepIsAcceptedForClockSkewTolerance() throws Exception {
    String secret = enrollAndConfirm().secret();
    LoginResponse login = login();

    verify(login.mfaChallengeToken(), codeForStepOffset(secret, -1)).expectStatus().isOk();
  }

  @Test
  void aChallengeTokenIsNotAnAccessTokenAndAnAccessTokenIsNotAChallengeToken() throws Exception {
    Enrolled enrolled = enrollAndConfirm();
    LoginResponse login = login();

    client()
        .get()
        .uri("/api/v1/sessions")
        .header("Authorization", "Bearer " + login.mfaChallengeToken())
        .exchange()
        .expectStatus()
        .isEqualTo(HttpStatus.UNAUTHORIZED);

    verify(enrolled.accessToken(), currentCode(enrolled.secret()))
        .expectStatus()
        .isEqualTo(HttpStatus.UNAUTHORIZED);
  }

  @Test
  void aGarbageChallengeTokenIsRejected() {
    verify("not-a-jwt", "123456").expectStatus().isEqualTo(HttpStatus.UNAUTHORIZED);
  }

  @Test
  void anAlreadyIssuedChallengeCannotCompleteLoginOnceMfaWasDisabledAndReEnrolledWithANewSecret()
      throws Exception {
    Enrolled first = enrollAndConfirm();
    String firstSecret = first.secret();
    String accessToken = first.accessToken();
    LoginResponse login = login();

    disable(accessToken, PASSWORD).expectStatus().isNoContent();
    MfaEnrollmentResponse second = enroll(accessToken, PASSWORD);
    confirm(accessToken, currentCode(second.secret())).expectStatus().isNoContent();

    // Same challenge, but the old authenticator's code no longer verifies against the new secret.
    verify(login.mfaChallengeToken(), staleAgainstSecret(firstSecret, second.secret()))
        .expectStatus()
        .isEqualTo(HttpStatus.UNAUTHORIZED);
  }

  @Test
  void disablingMfaRequiresThePasswordAndRestoresSingleStepLogin() throws Exception {
    String accessToken = enrollAndConfirm().accessToken();

    disable(accessToken, "not-the-password").expectStatus().isEqualTo(HttpStatus.UNAUTHORIZED);
    assertThat(admin().isMfaEnabled()).isTrue();

    disable(accessToken, PASSWORD).expectStatus().isNoContent();

    AppUser admin = admin();
    assertThat(admin.isMfaEnabled()).isFalse();
    assertThat(admin.getMfaTotpSecret()).isNull();
    LoginResponse login = login();
    assertThat(login.mfaRequired()).isFalse();
    assertThat(login.tokens()).isNotNull();
  }

  @Test
  void wrongCodesAtTheSecondStepCountTowardLockoutAndACorrectCodeIsThenRefused() throws Exception {
    String secret = enrollAndConfirm().secret();
    LoginResponse login = login();

    for (int attempt = 1; attempt <= 5; attempt++) {
      verify(login.mfaChallengeToken(), wrongCode(secret))
          .expectStatus()
          .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    assertThat(admin().getFailedLoginCount()).isEqualTo(5);
    // Locked: even the genuinely correct code, on a still-valid challenge, is refused.
    verify(login.mfaChallengeToken(), currentCode(secret))
        .expectStatus()
        .isEqualTo(HttpStatus.LOCKED);
  }

  @Test
  void repeatingTheFirstStepDoesNotTopUpTheWrongCodeBudget() throws Exception {
    String secret = enrollAndConfirm().secret();

    for (int attempt = 1; attempt <= 3; attempt++) {
      verify(login().mfaChallengeToken(), wrongCode(secret))
          .expectStatus()
          .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    // Three wrong codes, each preceded by a fresh (correct-password) first step: the counter must
    // have accumulated, not been reset by every first step.
    assertThat(admin().getFailedLoginCount()).isEqualTo(3);
  }

  @Test
  void aCompletedMfaLoginResetsTheFailureCounter() throws Exception {
    String secret = enrollAndConfirm().secret();
    LoginResponse login = login();
    verify(login.mfaChallengeToken(), wrongCode(secret))
        .expectStatus()
        .isEqualTo(HttpStatus.UNAUTHORIZED);
    assertThat(admin().getFailedLoginCount()).isEqualTo(1);

    verify(login.mfaChallengeToken(), currentCode(secret)).expectStatus().isOk();

    assertThat(admin().getFailedLoginCount()).isZero();
    assertThat(admin().getLastLoginAt()).isNotNull();
  }

  @Test
  void enrollingWhileMfaIsEnabledIsRefusedAndLeavesTheLiveSecretWorking() throws Exception {
    Enrolled enrolled = enrollAndConfirm();

    client()
        .post()
        .uri("/api/v1/users/me/mfa/enroll")
        .header("Authorization", "Bearer " + enrolled.accessToken())
        .contentType(MediaType.APPLICATION_JSON)
        .body(new MfaEnrollmentRequest(PASSWORD))
        .exchange()
        .expectStatus()
        .isEqualTo(HttpStatus.CONFLICT);

    // The original authenticator still logs in - the live secret was not replaced.
    verify(login().mfaChallengeToken(), currentCode(enrolled.secret())).expectStatus().isOk();
  }

  @Test
  void wrongPasswordsAtEnrollAndDisableShareTheLoginLockoutBudget() {
    String accessToken = bootstrapAdministrator();

    for (int attempt = 1; attempt <= 5; attempt++) {
      disable(accessToken, "not-the-password").expectStatus().isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    assertThat(admin().getFailedLoginCount()).isEqualTo(5);
    // Locked out of guessing further - even the correct password is refused at both endpoints...
    disable(accessToken, PASSWORD).expectStatus().isEqualTo(HttpStatus.LOCKED);
    client()
        .post()
        .uri("/api/v1/users/me/mfa/enroll")
        .header("Authorization", "Bearer " + accessToken)
        .contentType(MediaType.APPLICATION_JSON)
        .body(new MfaEnrollmentRequest(PASSWORD))
        .exchange()
        .expectStatus()
        .isEqualTo(HttpStatus.LOCKED);
    // ...and at login itself, since it is one shared per-account budget.
    client()
        .post()
        .uri("/api/v1/auth/login")
        .contentType(MediaType.APPLICATION_JSON)
        .body(new LoginRequest(EMAIL, PASSWORD))
        .exchange()
        .expectStatus()
        .isEqualTo(HttpStatus.LOCKED);
  }

  // ---- helpers ------------------------------------------------------------------------------

  private record Enrolled(String accessToken, String secret) {}

  private Enrolled enrollAndConfirm() throws Exception {
    String accessToken = bootstrapAdministrator();
    MfaEnrollmentResponse enrollment = enroll(accessToken, PASSWORD);
    confirm(accessToken, currentCode(enrollment.secret())).expectStatus().isNoContent();
    return new Enrolled(accessToken, enrollment.secret());
  }

  private MfaEnrollmentResponse enroll(String accessToken, String password) {
    return client()
        .post()
        .uri("/api/v1/users/me/mfa/enroll")
        .header("Authorization", "Bearer " + accessToken)
        .contentType(MediaType.APPLICATION_JSON)
        .body(new MfaEnrollmentRequest(password))
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody(MfaEnrollmentResponse.class)
        .returnResult()
        .getResponseBody();
  }

  private RestTestClient.ResponseSpec confirm(String accessToken, String code) {
    return client()
        .post()
        .uri("/api/v1/users/me/mfa/confirm")
        .header("Authorization", "Bearer " + accessToken)
        .contentType(MediaType.APPLICATION_JSON)
        .body(new MfaConfirmRequest(code))
        .exchange();
  }

  private RestTestClient.ResponseSpec disable(String accessToken, String password) {
    return client()
        .post()
        .uri("/api/v1/users/me/mfa/disable")
        .header("Authorization", "Bearer " + accessToken)
        .contentType(MediaType.APPLICATION_JSON)
        .body(new MfaDisableRequest(password))
        .exchange();
  }

  private RestTestClient.ResponseSpec verify(String challengeToken, String code) {
    return client()
        .post()
        .uri("/api/v1/auth/mfa/verify")
        .contentType(MediaType.APPLICATION_JSON)
        .body(new MfaVerifyRequest(challengeToken, code))
        .exchange();
  }

  private LoginResponse login() {
    return client()
        .post()
        .uri("/api/v1/auth/login")
        .contentType(MediaType.APPLICATION_JSON)
        .body(new LoginRequest(EMAIL, PASSWORD))
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody(LoginResponse.class)
        .returnResult()
        .getResponseBody();
  }

  private String bootstrapAdministrator() {
    return client()
        .post()
        .uri("/api/v1/setup/administrator")
        .contentType(MediaType.APPLICATION_JSON)
        .body(new SetupAdministratorRequest(EMAIL, PASSWORD, "Test Workspace", "CHF"))
        .exchange()
        .expectStatus()
        .isEqualTo(HttpStatus.CREATED)
        .expectBody(AuthTokensResponse.class)
        .returnResult()
        .getResponseBody()
        .accessToken();
  }

  private AppUser admin() {
    return appUserRepository.findByEmail(EMAIL).orElseThrow();
  }

  private String currentCode(String secret) throws Exception {
    return codeForStepOffset(secret, 0);
  }

  private String codeForStepOffset(String secret, int stepOffset) throws Exception {
    return codeGenerator.generate(secret, currentStep() + stepOffset);
  }

  private long currentStep() {
    return Math.floorDiv(Instant.now().getEpochSecond(), PERIOD_SECONDS);
  }

  // Codes the server accepts right now: the current step and one either side (RFC 6238 default
  // skew tolerance) - anything else generated from the same secret is by definition not accepted.
  private Set<String> acceptedCodes(String secret) throws Exception {
    Set<String> accepted = new HashSet<>();
    for (int offset = -1; offset <= 1; offset++) {
      accepted.add(codeForStepOffset(secret, offset));
    }
    return accepted;
  }

  // A real, well-formed code that is guaranteed not to be currently valid - the first code from a
  // time step far outside the tolerance window that doesn't collide (a 1-in-a-million chance) with
  // an accepted one.
  private String staleCode(String secret) throws Exception {
    Set<String> accepted = acceptedCodes(secret);
    for (int offset = -10; offset >= -100; offset--) {
      String candidate = codeForStepOffset(secret, offset);
      if (!accepted.contains(candidate)) {
        return candidate;
      }
    }
    throw new IllegalStateException("unreachable: 90 consecutive collisions");
  }

  private String wrongCode(String secret) throws Exception {
    Set<String> accepted = acceptedCodes(secret);
    for (int candidate = 0; candidate < 1_000_000; candidate++) {
      String code = "%06d".formatted(candidate);
      if (!accepted.contains(code)) {
        return code;
      }
    }
    throw new IllegalStateException("unreachable");
  }

  // A code valid for oldSecret right now that is not also valid for newSecret.
  private String staleAgainstSecret(String oldSecret, String newSecret) throws Exception {
    Set<String> acceptedByNew = acceptedCodes(newSecret);
    for (int offset = 0; offset >= -1; offset--) {
      String candidate = codeForStepOffset(oldSecret, offset);
      if (!acceptedByNew.contains(candidate)) {
        return candidate;
      }
    }
    return wrongCode(newSecret);
  }

  private RestTestClient client() {
    return RestTestClient.bindToServer().baseUrl("http://localhost:%d".formatted(port)).build();
  }
}
