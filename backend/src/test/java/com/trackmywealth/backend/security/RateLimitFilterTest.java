package com.trackmywealth.backend.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.trackmywealth.backend.dto.LoginRequest;
import com.trackmywealth.backend.dto.MfaConfirmRequest;
import com.trackmywealth.backend.dto.MfaVerifyRequest;
import com.trackmywealth.backend.dto.RefreshTokenRequest;
import com.trackmywealth.backend.dto.SetupAdministratorRequest;
import java.net.URI;
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
 * FR-AUT-010 (#48): per-source (IP) request limits, exercised end to end through the real Spring
 * Security filter chain since the whole point is to reject a request before it ever reaches {@link
 * JwtAuthenticationFilter} or the controller (see {@link RateLimitFilter}).
 *
 * <p>Overrides tight capacities via {@code @DynamicPropertySource} rather than using the production
 * defaults (application.yml): a 60-second refill window at production capacity would make this test
 * either slow (waiting out the window) or need 10+ rapid requests to prove anything. Every other
 * full-context test in this project explicitly disables rate limiting instead, since it isn't what
 * they're testing - that split is what makes production's secure-by-default {@code enabled: true}
 * safe to ship without breaking them.
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class RateLimitFilterTest {

  private static final String PASSWORD = "correct-horse-battery-staple";
  private static final int CAPACITY = 3;

  @Container
  static PostgreSQLContainer<?> postgres =
      new PostgreSQLContainer<>("postgres:16")
          .withDatabaseName("trackmywealth")
          .withUsername("trackmywealth")
          .withPassword("trackmywealth");

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", postgres::getJdbcUrl);
    registry.add("spring.datasource.username", postgres::getUsername);
    registry.add("spring.datasource.password", postgres::getPassword);
    registry.add("app.rate-limit.enabled", () -> "true");
    registry.add("app.rate-limit.login.capacity", () -> String.valueOf(CAPACITY));
    registry.add("app.rate-limit.login.refill-period", () -> "1m");
    registry.add("app.rate-limit.refresh.capacity", () -> String.valueOf(CAPACITY));
    registry.add("app.rate-limit.refresh.refill-period", () -> "1m");
    registry.add("app.rate-limit.setup.capacity", () -> String.valueOf(CAPACITY));
    registry.add("app.rate-limit.setup.refill-period", () -> "1m");
    registry.add("app.rate-limit.session-revoke.capacity", () -> String.valueOf(CAPACITY));
    registry.add("app.rate-limit.session-revoke.refill-period", () -> "1m");
    registry.add("app.rate-limit.mfa-verify.capacity", () -> String.valueOf(CAPACITY));
    registry.add("app.rate-limit.mfa-verify.refill-period", () -> "1m");
    registry.add("app.rate-limit.mfa-confirm.capacity", () -> String.valueOf(CAPACITY));
    registry.add("app.rate-limit.mfa-confirm.refill-period", () -> "1m");
    registry.add("app.rate-limit.security-create.capacity", () -> String.valueOf(CAPACITY));
    registry.add("app.rate-limit.security-create.refill-period", () -> "1m");
    registry.add("app.rate-limit.security-lookup.capacity", () -> String.valueOf(CAPACITY));
    registry.add("app.rate-limit.security-lookup.refill-period", () -> "1m");
  }

  @LocalServerPort int port;

  // A single test method, deliberately: @SpringBootTest shares one application context (and so
  // one RateLimitFilter singleton, with its own bucket map) across every test method in this
  // class, so splitting login/refresh/setup assertions into separate methods would have each
  // one's calls silently consume from the same still-warm buckets the previous method already
  // spent down, rather than each starting fresh.
  @Test
  void perSourceRateLimitingIsEnforcedIndependentlyPerRuleAndAccount() {
    // login: a spray attack targets many different (here, nonexistent) accounts from one IP,
    // never tripping any single account's own lockout - the limiter must still catch it, keyed by
    // source IP alone, independent of which account is targeted. No administrator needs to exist
    // for this - a nonexistent email already returns 401 (see LoginService).
    for (int i = 0; i < CAPACITY; i++) {
      login("nobody" + i + "@example.com").expectStatus().isEqualTo(HttpStatus.UNAUTHORIZED);
    }
    // Percent-encoded path ("i" -> "%69"), deliberately: request.getRequestURI() is never
    // decoded per the Servlet spec, so if the filter matched against it directly this call would
    // sail through unrate-limited (a fresh, un-throttled implicit pass) while still reaching
    // AuthController normally, since Spring MVC routes on the decoded path regardless. Getting a
    // 429 here instead of a 401 proves the filter matches on the same decoded path Spring MVC
    // does.
    client()
        .post()
        // A raw URI, not a String template: .uri(String) treats its argument as a template and
        // would re-encode the literal "%" itself (producing "%2569n", which decodes back to the
        // harmless literal text "%69n" - not what this assertion needs to prove).
        .uri(URI.create("http://localhost:%d/api/v1/auth/log%%69n".formatted(port)))
        .contentType(MediaType.APPLICATION_JSON)
        .body(new LoginRequest("one-more@example.com", PASSWORD))
        .exchange()
        .expectStatus()
        .isEqualTo(HttpStatus.TOO_MANY_REQUESTS)
        .expectHeader()
        .exists("Retry-After");

    // #60: no server.tomcat.remoteip.internal-proxies is configured in this context (the
    // application.yml default - nobody is trusted), so a spoofed X-Forwarded-For claiming a
    // fresh source must NOT be honored - this request is still every caller's real address
    // (127.0.0.1, since the test client connects over loopback), which the block above already
    // exhausted. A fresh 401 here instead of 429 would mean the header was trusted with no
    // allowlist configured at all - exactly the unconditional-trust regression #60 avoids by
    // deferring to Tomcat's own allowlisted RemoteIpValve instead of reading the header directly.
    client()
        .post()
        .uri("/api/v1/auth/login")
        .header("X-Forwarded-For", "203.0.113.99")
        .contentType(MediaType.APPLICATION_JSON)
        .body(new LoginRequest("still-should-be-limited@example.com", PASSWORD))
        .exchange()
        .expectStatus()
        .isEqualTo(HttpStatus.TOO_MANY_REQUESTS);

    // refresh: a completely separate rule's bucket - login's being exhausted above must not bleed
    // into it (an invalid token still gets its normal 401, not a 429).
    client()
        .post()
        .uri("/api/v1/auth/refresh")
        .contentType(MediaType.APPLICATION_JSON)
        .body(new RefreshTokenRequest("not-a-real-token"))
        .exchange()
        .expectStatus()
        .isEqualTo(HttpStatus.UNAUTHORIZED);

    // setup: succeeds at most once (it self-disables) - every call after the first is a business
    // 409, not a 429, until the rate limit itself is exhausted.
    for (int i = 0; i < CAPACITY; i++) {
      client()
          .post()
          .uri("/api/v1/setup/administrator")
          .contentType(MediaType.APPLICATION_JSON)
          .body(
              new SetupAdministratorRequest(
                  "admin" + i + "@example.com", PASSWORD, "Test Workspace", "CHF"))
          .exchange()
          .expectStatus()
          .value(status -> assertThat(status).isNotEqualTo(HttpStatus.TOO_MANY_REQUESTS.value()));
    }
    client()
        .post()
        .uri("/api/v1/setup/administrator")
        .contentType(MediaType.APPLICATION_JSON)
        .body(
            new SetupAdministratorRequest(
                "one-more@example.com", PASSWORD, "Test Workspace", "CHF"))
        .exchange()
        .expectStatus()
        .isEqualTo(HttpStatus.TOO_MANY_REQUESTS);

    // session-revoke: the filter runs before authentication (SecurityConfig), so an entirely
    // unauthenticated call still consumes from this rule's bucket - proving issue #57's cheap-
    // hammer scenario (each denial doubling DB writes via AuthorizationDenialAuditService's
    // REQUIRES_NEW write) is throttled independent of whether the caller ever authenticates.
    for (int i = 0; i < CAPACITY; i++) {
      revokeSession(UUID.randomUUID()).expectStatus().isEqualTo(HttpStatus.UNAUTHORIZED);
    }
    revokeSession(UUID.randomUUID()).expectStatus().isEqualTo(HttpStatus.TOO_MANY_REQUESTS);

    // US-02-04's two TOTP-code endpoints: a 6-digit code is brute-forceable, so each has its own
    // bucket. mfa-verify is reachable with no token at all; mfa-confirm is authenticated, but as
    // with session-revoke the filter runs before authentication, so an unauthenticated call still
    // consumes from its bucket.
    for (int i = 0; i < CAPACITY; i++) {
      mfaVerify().expectStatus().isEqualTo(HttpStatus.UNAUTHORIZED);
    }
    mfaVerify().expectStatus().isEqualTo(HttpStatus.TOO_MANY_REQUESTS);

    for (int i = 0; i < CAPACITY; i++) {
      mfaConfirm().expectStatus().isEqualTo(HttpStatus.UNAUTHORIZED);
    }
    mfaConfirm().expectStatus().isEqualTo(HttpStatus.TOO_MANY_REQUESTS);

    // US-12-01: creating shared security-master rows is limited per source too.
    for (int i = 0; i < CAPACITY; i++) {
      createSecurity().expectStatus().isEqualTo(HttpStatus.UNAUTHORIZED);
    }
    createSecurity().expectStatus().isEqualTo(HttpStatus.TOO_MANY_REQUESTS);

    // The lookup has its own, separate bucket: exhausting the create rule above must not have
    // spent it, and exhausting it here must not be what stops a create (that already returned 429
    // on its own rule). Both are verbs on one path, so a filter keyed by path alone would conflate
    // them. Metered because the master is cross-tenant reference data whose membership is itself a
    // signal - ADR 0003, not because the query is expensive.
    for (int i = 0; i < CAPACITY; i++) {
      lookupSecurity().expectStatus().isEqualTo(HttpStatus.UNAUTHORIZED);
    }
    lookupSecurity().expectStatus().isEqualTo(HttpStatus.TOO_MANY_REQUESTS);

    // Still keyed per rule *and* per path: the by-id read has no rule of its own and stays open,
    // since an unguessable UUID is not enumerable the way the published ISIN list is.
    client()
        .get()
        .uri("/api/v1/securities/" + UUID.randomUUID())
        .exchange()
        .expectStatus()
        .isEqualTo(HttpStatus.UNAUTHORIZED);
  }

  private RestTestClient.ResponseSpec createSecurity() {
    return client()
        .post()
        .uri("/api/v1/securities")
        .contentType(MediaType.APPLICATION_JSON)
        .body("{}")
        .exchange();
  }

  private RestTestClient.ResponseSpec lookupSecurity() {
    return client().get().uri("/api/v1/securities?isin=IE00B4L5Y983").exchange();
  }

  private RestTestClient.ResponseSpec mfaVerify() {
    return client()
        .post()
        .uri("/api/v1/auth/mfa/verify")
        .contentType(MediaType.APPLICATION_JSON)
        .body(new MfaVerifyRequest("not-a-real-challenge", "123456"))
        .exchange();
  }

  private RestTestClient.ResponseSpec mfaConfirm() {
    return client()
        .post()
        .uri("/api/v1/users/me/mfa/confirm")
        .contentType(MediaType.APPLICATION_JSON)
        .body(new MfaConfirmRequest("123456"))
        .exchange();
  }

  private RestTestClient.ResponseSpec revokeSession(UUID id) {
    return client().post().uri("/api/v1/sessions/" + id + "/revoke").exchange();
  }

  private RestTestClient.ResponseSpec login(String email) {
    return client()
        .post()
        .uri("/api/v1/auth/login")
        .contentType(MediaType.APPLICATION_JSON)
        .body(new LoginRequest(email, PASSWORD))
        .exchange();
  }

  private RestTestClient client() {
    return RestTestClient.bindToServer().baseUrl("http://localhost:%d".formatted(port)).build();
  }
}
