package com.trackmywealth.backend.security;

import com.trackmywealth.backend.dto.LoginRequest;
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
 * #60: {@code server.tomcat.remoteip.internal-proxies} (env {@code TRUSTED_PROXIES}) is what lets
 * {@link RateLimitFilter} see a real client's address instead of a shared reverse proxy's - this
 * trusts loopback (127.0.0.1, since the test client always connects that way) specifically so these
 * tests can control the "source" address via X-Forwarded-For, the same way a real trusted proxy
 * would set it. {@link RateLimitFilterTest} covers the opposite (default, untrusted) case, where a
 * spoofed header must be ignored - a separate context is needed here since this property can only
 * be set once, at context startup.
 *
 * <p>Piggybacks on this to also prove {@link RateLimitFilter}'s IPv6 /64 bucketing (two addresses
 * in the same /64 share a bucket, two in different /64s don't - including when "::" compression
 * overlaps the prefix itself) and its hand-rolled, DNS-free parsing of the forwarded value (a
 * malformed one degrades to an opaque bucket key rather than erroring or blocking).
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class RateLimitFilterTrustedProxyTest {

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
    registry.add("server.tomcat.remoteip.internal-proxies", () -> "127\\.0\\.0\\.1");
  }

  @LocalServerPort int port;

  // Two independent scenarios, deliberately kept in separate methods here (unlike
  // RateLimitFilterTest's single method): each uses its own set of source addresses, so unlike
  // that class's shared 127.0.0.1-keyed buckets, there's no risk of one method's exhausted bucket
  // bleeding into the other's assertions regardless of execution order.

  @Test
  void distinctForwardedAddressesAreBucketedIndependently() {
    for (int i = 0; i < CAPACITY; i++) {
      login("203.0.113.1", "a" + i + "@example.com")
          .expectStatus()
          .isEqualTo(HttpStatus.UNAUTHORIZED);
    }
    login("203.0.113.1", "one-more-a@example.com")
        .expectStatus()
        .isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
    // A different forwarded address is unaffected by 203.0.113.1's exhausted bucket - proves the
    // trusted peer's header is actually driving per-source bucketing, not collapsing everyone
    // into one shared bucket the way the untrusted default would.
    login("203.0.113.2", "still-fine@example.com")
        .expectStatus()
        .isEqualTo(HttpStatus.UNAUTHORIZED);
  }

  @Test
  void ipv6AddressesInTheSameSlash64ShareABucketButADifferentSlash64DoesNot() {
    // Same /64 (2001:db8:1:1::/64), different host bits within it.
    for (int i = 0; i < CAPACITY; i++) {
      login("2001:db8:1:1::" + (i + 1), "b" + i + "@example.com")
          .expectStatus()
          .isEqualTo(HttpStatus.UNAUTHORIZED);
    }
    login("2001:db8:1:1::" + (CAPACITY + 1), "one-more-b@example.com")
        .expectStatus()
        .isEqualTo(HttpStatus.TOO_MANY_REQUESTS);

    // A different /64 must not have been affected by the block above.
    login("2001:db8:1:2::1", "still-fine-b@example.com")
        .expectStatus()
        .isEqualTo(HttpStatus.UNAUTHORIZED);
  }

  @Test
  void compressedLeadingZeroGroupsCollapseToTheSameSlash64() {
    // "::1" and "::2" both expand with an all-zero /64 prefix (the "::" falls entirely within
    // the first four groups) - proves the hand-rolled parser (#60) correctly resolves the
    // prefix when compression overlaps it, not just when it's confined to the host bits, as in
    // ipv6AddressesInTheSameSlash64ShareABucketButADifferentSlash64DoesNot above.
    for (int i = 0; i < CAPACITY; i++) {
      login("::" + (i + 1), "d" + i + "@example.com")
          .expectStatus()
          .isEqualTo(HttpStatus.UNAUTHORIZED);
    }
    login("::" + (CAPACITY + 1), "one-more-d@example.com")
        .expectStatus()
        .isEqualTo(HttpStatus.TOO_MANY_REQUESTS);

    // A fully-expanded (no "::" at all), unrelated /64 must be unaffected.
    login("2001:0db8:0002:0000:0000:0000:0000:0001", "still-fine-d@example.com")
        .expectStatus()
        .isEqualTo(HttpStatus.UNAUTHORIZED);
  }

  @Test
  void malformedForwardedValueDegradesSafelyToAnOpaqueBucketKey() {
    // Contains a colon but isn't a valid IPv6 literal. Proves the hand-rolled parser (#60) falls
    // back to using it as an opaque string key rather than throwing - and, since this request
    // completes at all instead of hanging, that nothing here ever attempts to resolve it as a
    // hostname (the DNS-blocking risk a java.net.InetAddress-based parse would have had).
    for (int i = 0; i < CAPACITY; i++) {
      login("not:a:valid:ipv6:address", "e" + i + "@example.com")
          .expectStatus()
          .isEqualTo(HttpStatus.UNAUTHORIZED);
    }
    login("not:a:valid:ipv6:address", "one-more-e@example.com")
        .expectStatus()
        .isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
  }

  private RestTestClient.ResponseSpec login(String forwardedFor, String email) {
    return RestTestClient.bindToServer()
        .baseUrl("http://localhost:%d".formatted(port))
        .build()
        .post()
        .uri("/api/v1/auth/login")
        .header("X-Forwarded-For", forwardedFor)
        .contentType(MediaType.APPLICATION_JSON)
        .body(new LoginRequest(email, PASSWORD))
        .exchange();
  }
}
