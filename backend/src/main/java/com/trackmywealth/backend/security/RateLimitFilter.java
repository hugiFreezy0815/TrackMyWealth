package com.trackmywealth.backend.security;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.trackmywealth.backend.config.RateLimitProperties;
import com.trackmywealth.backend.controller.AuthController;
import com.trackmywealth.backend.controller.SessionController;
import com.trackmywealth.backend.controller.SetupController;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.ConsumptionProbe;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Optional;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.UrlPathHelper;

/**
 * FR-AUT-010 (#48): per-source (IP) request limits on authentication-adjacent endpoints,
 * independent of {@code app_user}'s own per-account lockout (US-02-02's {@code
 * failed_login_count}/{@code locked_until} in {@code LoginService}) - without this, an attacker
 * sprays low-and-slow guesses across many different accounts from one IP without ever tripping any
 * single account's lockout.
 *
 * <p>Covers {@link AuthController#LOGIN_PATH}, {@link AuthController#REFRESH_PATH}, {@link
 * SetupController#ADMINISTRATOR_PATH} (unauthenticated and brute-forceable until first use), and
 * {@link SessionController#REVOKE_PATH} (authenticated, but issue #57 noted a caller can already
 * cheaply hammer it - each denial doubles DB connection usage via {@code
 * AuthorizationDenialAuditService}'s {@code REQUIRES_NEW} write). Referencing each controller's own
 * constant, rather than a re-typed literal, means the two can never silently drift apart the way a
 * second hardcoded copy of the same path could.
 *
 * <p>Runs before {@link JwtAuthenticationFilter} in {@code SecurityConfig} - an excess request is
 * rejected before it costs a JWT parse or an {@code AppUserRepository.findAuthSnapshot} query, not
 * just before the endpoint's own business logic. Matches against the request's decoded path (via
 * {@link UrlPathHelper}, the same resolution Spring MVC's own routing uses) rather than {@link
 * HttpServletRequest#getRequestURI()} directly - the raw URI is never percent-decoded per the
 * Servlet spec, so matching against it would let a trivially percent-encoded path (e.g. {@code
 * /api/v1/auth/log%69n}) reach the real, decoded-path-routed controller while sailing past every
 * check here unrate-limited.
 *
 * <p>Identifies a source via {@link HttpServletRequest#getRemoteAddr()} - as of #60, that is no
 * longer necessarily the direct TCP peer: {@code server.forward-headers-strategy=native}
 * (application.yml) enables Tomcat's {@code RemoteIpValve}, which rewrites it from X-Forwarded-For,
 * but *only* when the direct peer's address matches the configured {@code
 * server.tomcat.remoteip.internal-proxies} allowlist (the {@code TRUSTED_PROXIES} env var - empty
 * by default, meaning nobody is trusted and every client's own direct address is used, exactly as
 * before #60). This is deliberately configured at the container level rather than by hand-parsing
 * the header in this class: a naive, untrusted X-Forwarded-For read would let any direct caller
 * spoof a fresh value per request and bypass the limiter entirely - worse than not reading the
 * header at all - and Tomcat's valve already implements the trusted-proxy check correctly.
 *
 * <p>IPv6 addresses are bucketed by their /64 network prefix, not the full 128-bit address ({@link
 * #sourceKey}) - an attacker with a routed IPv6 /64 (trivially obtainable from most ISP
 * allocations) could otherwise mint an unlimited number of fresh, full-capacity buckets by using a
 * different address within that /64 on every request. IPv4 addresses are used in full, since IPv4
 * allocations of that scale aren't handed to individual end users.
 *
 * <p>One in-memory token bucket per (rule, source key) pair, via bucket4j - sufficient for this
 * project's current single-instance self-hosted deployment target (see #48's own open question;
 * revisit with a shared/Redis-backed bucket store if multi-instance deployment ever becomes a
 * goal). The bucket store ({@link #buckets}) is a Caffeine cache bounded by {@code
 * app.rate-limit.max-buckets} (total across all four rules) with time-based eviction of idle
 * entries - #60's fix for the unbounded-growth gap the IPv6 bucketing above would otherwise make
 * easy to trigger deliberately.
 */
@Component
public class RateLimitFilter extends OncePerRequestFilter {

  private static final AntPathMatcher PATH_MATCHER = new AntPathMatcher();
  private static final UrlPathHelper URL_PATH_HELPER = UrlPathHelper.defaultInstance;

  // Generous relative to every configured refill-period (all far under an hour in practice) -
  // this never evicts a bucket that's still actively limiting someone, only ones truly idle.
  private static final Duration STALE_AFTER = Duration.ofHours(1);

  private final Cache<String, Bucket> buckets;
  private final boolean enabled;
  private final Rule loginRule;
  private final Rule refreshRule;
  private final Rule setupRule;
  private final Rule sessionRevokeRule;

  // Rule instances built once at startup, not per request: these four (name, config) pairings
  // are fixed for the lifetime of the application (RateLimitProperties is immutable), so
  // allocating a fresh Rule on every rate-limited request - exactly the traffic this filter
  // exists to defend against - would be avoidable garbage generated precisely when the JVM is
  // under the most load.
  public RateLimitFilter(RateLimitProperties properties) {
    this.enabled = properties.enabled();
    this.loginRule = new Rule("login", properties.login());
    this.refreshRule = new Rule("refresh", properties.refresh());
    this.setupRule = new Rule("setup", properties.setup());
    this.sessionRevokeRule = new Rule("sessionRevoke", properties.sessionRevoke());
    this.buckets =
        Caffeine.newBuilder()
            .maximumSize(properties.maxBuckets())
            .expireAfterAccess(STALE_AFTER)
            .build();
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
      throws ServletException, IOException {
    if (!enabled) {
      filterChain.doFilter(request, response);
      return;
    }

    Optional<Rule> rule =
        ruleFor(request.getMethod(), URL_PATH_HELPER.getPathWithinApplication(request));
    if (rule.isEmpty()) {
      filterChain.doFilter(request, response);
      return;
    }

    Bucket bucket = bucketFor(rule.get(), request.getRemoteAddr());
    ConsumptionProbe probe = bucket.tryConsumeAndReturnRemaining(1);
    if (probe.isConsumed()) {
      filterChain.doFilter(request, response);
      return;
    }

    response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
    // Rounded up, not truncated: refillGreedy trickles tokens back continuously rather than all
    // at once at the period boundary, so the actual wait is usually well under the full configured
    // refill-period - reporting a truncated (possibly zero) value could tell a client to retry
    // before a token is actually available.
    long retryAfterSeconds = (probe.getNanosToWaitForRefill() + 999_999_999L) / 1_000_000_000L;
    response.setHeader("Retry-After", String.valueOf(retryAfterSeconds));
  }

  private Optional<Rule> ruleFor(String method, String path) {
    if (!"POST".equals(method)) {
      return Optional.empty();
    }
    if (AuthController.LOGIN_PATH.equals(path)) {
      return Optional.of(loginRule);
    }
    if (AuthController.REFRESH_PATH.equals(path)) {
      return Optional.of(refreshRule);
    }
    if (SetupController.ADMINISTRATOR_PATH.equals(path)) {
      return Optional.of(setupRule);
    }
    // AntPathMatcher supports the same {id} placeholder syntax as Spring MVC's own @PostMapping,
    // so this is the exact same literal SessionController maps - not a hand-translated wildcard
    // that could drift from it.
    if (PATH_MATCHER.match(SessionController.REVOKE_PATH, path)) {
      return Optional.of(sessionRevokeRule);
    }
    return Optional.empty();
  }

  private Bucket bucketFor(Rule rule, String sourceIp) {
    String key = rule.name() + ':' + sourceKey(sourceIp);
    // get(key, mappingFunction) computes at most once per key even under concurrent first
    // access - no manual putIfAbsent race-handling needed, unlike a plain ConcurrentHashMap.
    return buckets.get(key, k -> newBucket(rule.config()));
  }

  // IPv6: collapse to the /64 network prefix (the top 8 of 16 address bytes) so an attacker
  // rotating through a single routed /64 can't mint an unlimited number of fresh buckets: one
  // per distinct address. IPv4: used as-is - allocations large enough for the same trick aren't
  // handed to individual end users.
  private static String sourceKey(String remoteAddr) {
    InetAddress address;
    try {
      // A literal IP address (always what getRemoteAddr()/RemoteIpValve produce) is only
      // format-validated here, never resolved via DNS.
      address = InetAddress.getByName(remoteAddr);
    } catch (UnknownHostException e) {
      return remoteAddr;
    }
    if (!(address instanceof Inet6Address)) {
      return remoteAddr;
    }
    byte[] prefix = Arrays.copyOf(address.getAddress(), 8);
    return HexFormat.of().formatHex(prefix);
  }

  private Bucket newBucket(RateLimitProperties.Rule config) {
    Bandwidth limit =
        Bandwidth.builder()
            .capacity(config.capacity())
            .refillGreedy(config.capacity(), config.refillPeriod())
            .build();
    return Bucket.builder().addLimit(limit).build();
  }

  private record Rule(String name, RateLimitProperties.Rule config) {}
}
