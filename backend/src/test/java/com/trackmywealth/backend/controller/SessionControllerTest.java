package com.trackmywealth.backend.controller;

import static org.assertj.core.api.Assertions.assertThat;

import com.trackmywealth.backend.dto.AuthTokensResponse;
import com.trackmywealth.backend.dto.CreateUserRequest;
import com.trackmywealth.backend.dto.LoginRequest;
import com.trackmywealth.backend.dto.LoginResponse;
import com.trackmywealth.backend.dto.RefreshTokenRequest;
import com.trackmywealth.backend.dto.SessionSummaryResponse;
import com.trackmywealth.backend.dto.SetupAdministratorRequest;
import com.trackmywealth.backend.dto.UserSummaryResponse;
import com.trackmywealth.backend.entity.AuthorizationDenialLog;
import com.trackmywealth.backend.repository.AuthorizationDenialLogRepository;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.client.RestTestClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * US-02-03's Definition of Done: listing a user's own active sessions, revoking one immediately
 * invalidating its access token (checked via a per-session marker, not JWT expiry, per FR-AUT-005),
 * and the story's explicit edge case of revoking the session making the request.
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SessionControllerTest {

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
  }

  @LocalServerPort int port;

  @Autowired DataSource dataSource;
  @Autowired AuthorizationDenialLogRepository authorizationDenialLogRepository;

  @BeforeEach
  void cleanDatabase() throws Exception {
    try (Connection connection = dataSource.getConnection();
        Statement statement = connection.createStatement()) {
      statement.execute(
          "TRUNCATE TABLE authorization_denial_log, admin_audit_log, user_session, refresh_token,"
              + " app_user, workspace_member, financial_institution, workspace RESTART IDENTITY"
              + " CASCADE");
    }
  }

  @Test
  void listingSessionsReturnsEveryActiveDeviceWithExactlyOneMarkedCurrent() {
    bootstrapAdministrator("laptop");
    AuthTokensResponse phone = login("admin@example.com", "phone");
    AuthTokensResponse tablet = login("admin@example.com", "tablet");

    List<SessionSummaryResponse> sessions = listSessions(tablet.accessToken());

    assertThat(sessions).hasSize(3);
    assertThat(sessions)
        .extracting(SessionSummaryResponse::deviceLabel)
        .containsExactlyInAnyOrder("laptop", "phone", "tablet");
    assertThat(sessions).allSatisfy(s -> assertThat(s.lastSeenAt()).isNotNull());
    assertThat(sessions.stream().filter(SessionSummaryResponse::current)).hasSize(1);
    assertThat(
            sessions.stream()
                .filter(SessionSummaryResponse::current)
                .findFirst()
                .orElseThrow()
                .deviceLabel())
        .isEqualTo("tablet");
  }

  @Test
  void revokingASessionInvalidatesItsAccessTokenBeforeTheNextRequestButNotThisOne() {
    bootstrapAdministrator("laptop");
    AuthTokensResponse phone = login("admin@example.com", "phone");
    UUID phoneSessionId = onlySessionWith(phone.accessToken(), "phone").id();

    // The story's explicit edge case: revoking from a DIFFERENT session (laptop, via a fresh
    // login) targets the phone session and must succeed normally.
    AuthTokensResponse laptop = login("admin@example.com", "laptop-2nd-login");
    revoke(laptop.accessToken(), phoneSessionId).expectStatus().isOk();

    // Same (now-revoked) phone access token, next request: rejected, not by waiting for JWT
    // expiry but via the per-session marker check (FR-AUT-005).
    listSessionsRaw(phone.accessToken()).expectStatus().isEqualTo(HttpStatus.UNAUTHORIZED);
  }

  @Test
  void revokingYourOwnCurrentSessionStillRespondsNormallyForThisRequest() {
    bootstrapAdministrator("laptop");
    AuthTokensResponse phone = login("admin@example.com", "phone");
    UUID phoneSessionId = onlySessionWith(phone.accessToken(), "phone").id();

    // Revoking the very session whose access token authenticated this request must still
    // complete with a normal response - the story's own explicit edge case.
    SessionSummaryResponse revoked =
        client()
            .post()
            .uri("/api/v1/sessions/" + phoneSessionId + "/revoke")
            .header("Authorization", "Bearer " + phone.accessToken())
            .exchange()
            .expectStatus()
            .isOk()
            .expectBody(SessionSummaryResponse.class)
            .returnResult()
            .getResponseBody();

    assertThat(revoked).isNotNull();
    assertThat(revoked.status()).isEqualTo("REVOKED");
    assertThat(revoked.current()).isTrue();

    // The *next* request with that same token is rejected.
    listSessionsRaw(phone.accessToken()).expectStatus().isEqualTo(HttpStatus.UNAUTHORIZED);
  }

  @Test
  void revokingASessionAlsoRevokesItsRefreshToken() {
    bootstrapAdministrator("laptop");
    AuthTokensResponse phone = login("admin@example.com", "phone");
    UUID phoneSessionId = onlySessionWith(phone.accessToken(), "phone").id();

    revoke(phone.accessToken(), phoneSessionId).expectStatus().isOk();

    client()
        .post()
        .uri("/api/v1/auth/refresh")
        .contentType(MediaType.APPLICATION_JSON)
        .body(new RefreshTokenRequest(phone.refreshToken()))
        .exchange()
        .expectStatus()
        .isEqualTo(HttpStatus.UNAUTHORIZED);
  }

  @Test
  void revokingASessionPersistsRevokedAtInTheDatabase() throws Exception {
    // Regression test: an earlier version of SessionService.revokeSession() mutated the loaded
    // (still JPA-managed) UserSession entity's status field to build the response, which made
    // Hibernate's implicit pre-commit flush dirty-check it and reissue a full-column UPDATE from
    // its stale in-memory state - silently clobbering revoked_at back to NULL right after the
    // bulk update above had set it. status ended up correctly 'REVOKED' (so JwtAuthenticationFilter
    // still denied the token), but the DB's revoked_at was wrong - checked directly here since
    // the API response alone can't reveal this class of bug.
    bootstrapAdministrator("laptop");
    AuthTokensResponse phone = login("admin@example.com", "phone");
    UUID phoneSessionId = onlySessionWith(phone.accessToken(), "phone").id();

    revoke(phone.accessToken(), phoneSessionId).expectStatus().isOk();

    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement =
            connection.prepareStatement("SELECT revoked_at FROM user_session WHERE id = ?")) {
      statement.setObject(1, phoneSessionId);
      try (ResultSet rs = statement.executeQuery()) {
        assertThat(rs.next()).isTrue();
        assertThat(rs.getTimestamp("revoked_at")).isNotNull();
      }
    }
  }

  @Test
  void revokedSessionsAreExcludedFromTheListing() {
    String laptopToken = bootstrapAdministrator("laptop");
    AuthTokensResponse phone = login("admin@example.com", "phone");
    UUID phoneSessionId = onlySessionWith(phone.accessToken(), "phone").id();

    revoke(laptopToken, phoneSessionId).expectStatus().isOk();

    assertThat(listSessions(laptopToken))
        .extracting(SessionSummaryResponse::deviceLabel)
        .containsExactly("laptop");
  }

  @Test
  void aUserCanNeitherListNorRevokeAnotherUsersSession() {
    String adminToken = bootstrapAdministrator("admin-device");
    createStandardUser(adminToken, "charlie@example.com");
    AuthTokensResponse charlieTokens = login("charlie@example.com", "charlie-device");
    UUID charlieSessionId = onlySessionWith(charlieTokens.accessToken(), "charlie-device").id();

    // Indistinguishable from a nonexistent session (FR-TEN-006) - never a 403.
    revoke(adminToken, charlieSessionId).expectStatus().isEqualTo(HttpStatus.NOT_FOUND);

    // Untouched: charlie's own token still authenticates and still sees the session as active.
    assertThat(listSessions(charlieTokens.accessToken()))
        .extracting(SessionSummaryResponse::id)
        .contains(charlieSessionId);
  }

  @Test
  void revokingAnotherUsersSessionWritesAnAuthorizationDenialLogRow() throws Exception {
    String adminToken = bootstrapAdministrator("admin-device");
    createStandardUser(adminToken, "charlie@example.com");
    AuthTokensResponse charlieTokens = login("charlie@example.com", "charlie-device");
    UUID charlieSessionId = onlySessionWith(charlieTokens.accessToken(), "charlie-device").id();
    UUID adminUserId = userIdByEmail("admin@example.com");

    revoke(adminToken, charlieSessionId).expectStatus().isEqualTo(HttpStatus.NOT_FOUND);

    List<AuthorizationDenialLog> denials = denialLogsFor(charlieSessionId);
    assertThat(denials).hasSize(1);
    assertThat(denials.get(0).getPrincipalUserId()).isEqualTo(adminUserId);
    assertThat(denials.get(0).getRequestedEntityType()).isEqualTo("UserSession");
    assertThat(denials.get(0).getReason()).isEqualTo("NOT_FOUND");
  }

  @Test
  void revokingAGenuinelyNonexistentSessionWritesTheSameKindOfDenialLogRow() throws Exception {
    String adminToken = bootstrapAdministrator("admin-device");
    UUID adminUserId = userIdByEmail("admin@example.com");
    UUID nonexistentSessionId = UUID.randomUUID();

    revoke(adminToken, nonexistentSessionId).expectStatus().isEqualTo(HttpStatus.NOT_FOUND);

    List<AuthorizationDenialLog> denials = denialLogsFor(nonexistentSessionId);
    assertThat(denials).hasSize(1);
    assertThat(denials.get(0).getPrincipalUserId()).isEqualTo(adminUserId);
    assertThat(denials.get(0).getRequestedEntityType()).isEqualTo("UserSession");
    // Same reason as the cross-user case above, deliberately: a caller-visible (or even
    // audit-log-visible) distinction between "not yours" and "doesn't exist" is exactly what
    // FR-TEN-006 forbids.
    assertThat(denials.get(0).getReason()).isEqualTo("NOT_FOUND");
  }

  @Test
  void nonexistentAndCrossUserSessionRevokesAreIndistinguishableInTiming() {
    String adminToken = bootstrapAdministrator("admin-device");
    createStandardUser(adminToken, "charlie@example.com");
    AuthTokensResponse charlieTokens = login("charlie@example.com", "charlie-device");
    UUID charlieSessionId = onlySessionWith(charlieTokens.accessToken(), "charlie-device").id();

    // Warm-up so JIT/connection-pool startup cost doesn't skew the very first measured calls.
    timeRevoke(adminToken, UUID.randomUUID());
    timeRevoke(adminToken, charlieSessionId);

    int iterations = 30;
    List<Duration> nonexistentTimings = new ArrayList<>();
    List<Duration> crossUserTimings = new ArrayList<>();
    for (int i = 0; i < iterations; i++) {
      nonexistentTimings.add(timeRevoke(adminToken, UUID.randomUUID()));
      crossUserTimings.add(timeRevoke(adminToken, charlieSessionId));
    }

    // Median, not mean: a single GC pause or scheduling blip on a shared/loaded CI runner during
    // just one of these 30 calls would skew an average enough to flake this assertion, but is
    // absorbed almost entirely by the median.
    double nonexistentMedianMillis = medianMillis(nonexistentTimings);
    double crossUserMedianMillis = medianMillis(crossUserTimings);

    // FR-TEN-006's "indistinguishable... in response and in timing": both paths run the exact
    // same code (a single scoped SELECT that finds zero rows either way, then the identical
    // denial-audit write and 404), so their timing should be close by construction, not because
    // of artificial padding. A 1ms floor keeps the ratio meaningful even when both medians are
    // very small (sub-millisecond noise shouldn't blow up the ratio), and a generous 4x tolerance
    // avoids flaking on a loaded CI runner while still catching a genuine future regression (e.g.
    // an extra lookup added to only one of the two paths that would let a caller distinguish
    // them).
    double floorMillis = 1.0;
    double ratio =
        (Math.max(nonexistentMedianMillis, crossUserMedianMillis) + floorMillis)
            / (Math.min(nonexistentMedianMillis, crossUserMedianMillis) + floorMillis);
    assertThat(ratio).isLessThan(4.0);
  }

  @Test
  void listSessionsRequiresAuthentication() {
    listSessionsRaw(null).expectStatus().isEqualTo(HttpStatus.UNAUTHORIZED);
  }

  private RestTestClient.ResponseSpec revoke(String accessToken, UUID sessionId) {
    return client()
        .post()
        .uri("/api/v1/sessions/" + sessionId + "/revoke")
        .header("Authorization", "Bearer " + accessToken)
        .exchange();
  }

  private Duration timeRevoke(String accessToken, UUID sessionId) {
    Instant start = Instant.now();
    revoke(accessToken, sessionId).expectStatus().isEqualTo(HttpStatus.NOT_FOUND);
    return Duration.between(start, Instant.now());
  }

  private double medianMillis(List<Duration> timings) {
    List<Long> sortedNanos = timings.stream().map(Duration::toNanos).sorted().toList();
    int size = sortedNanos.size();
    long medianNanos =
        size % 2 == 0
            ? (sortedNanos.get(size / 2 - 1) + sortedNanos.get(size / 2)) / 2
            : sortedNanos.get(size / 2);
    return medianNanos / 1_000_000.0;
  }

  private List<AuthorizationDenialLog> denialLogsFor(UUID requestedEntityId) {
    return authorizationDenialLogRepository.findByRequestedEntityId(requestedEntityId);
  }

  private UUID userIdByEmail(String email) throws Exception {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement =
            connection.prepareStatement("SELECT id FROM app_user WHERE email = ?")) {
      statement.setString(1, email);
      try (ResultSet rs = statement.executeQuery()) {
        assertThat(rs.next()).isTrue();
        return (UUID) rs.getObject("id");
      }
    }
  }

  private List<SessionSummaryResponse> listSessions(String accessToken) {
    return listSessionsRaw(accessToken)
        .expectStatus()
        .isOk()
        .expectBody(new ParameterizedTypeReference<List<SessionSummaryResponse>>() {})
        .returnResult()
        .getResponseBody();
  }

  private SessionSummaryResponse onlySessionWith(String accessToken, String deviceLabel) {
    return listSessions(accessToken).stream()
        .filter(s -> deviceLabel.equals(s.deviceLabel()))
        .findFirst()
        .orElseThrow();
  }

  private RestTestClient.ResponseSpec listSessionsRaw(String accessToken) {
    RestTestClient.RequestHeadersSpec<?> request = client().get().uri("/api/v1/sessions");
    if (accessToken != null) {
      request = request.header("Authorization", "Bearer " + accessToken);
    }
    return request.exchange();
  }

  private AuthTokensResponse login(String email, String deviceLabel) {
    LoginResponse response =
        client()
            .post()
            .uri("/api/v1/auth/login")
            .header("User-Agent", deviceLabel)
            .contentType(MediaType.APPLICATION_JSON)
            .body(new LoginRequest(email, PASSWORD))
            .exchange()
            .expectStatus()
            .isOk()
            .expectBody(LoginResponse.class)
            .returnResult()
            .getResponseBody();
    return response.tokens();
  }

  private String bootstrapAdministrator(String deviceLabel) {
    return client()
        .post()
        .uri("/api/v1/setup/administrator")
        .header("User-Agent", deviceLabel)
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
        .body(new CreateUserRequest(email, PASSWORD, "STANDARD_USER", "EN"))
        .exchange()
        .expectStatus()
        .isEqualTo(HttpStatus.CREATED)
        .expectBody(UserSummaryResponse.class);
  }

  private RestTestClient client() {
    return RestTestClient.bindToServer().baseUrl("http://localhost:%d".formatted(port)).build();
  }
}
