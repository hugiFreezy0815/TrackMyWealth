package com.trackmywealth.backend.security;

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
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
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
 * <p>Identifies a source purely by {@link HttpServletRequest#getRemoteAddr()}, with no
 * X-Forwarded-For/trusted-proxy support - correct for a bare, directly-exposed deployment, but
 * every client behind a shared reverse proxy (a near-universal way to add TLS in front of this
 * project's documented NAS deployment path) would collapse into one shared bucket per rule,
 * silently defeating the isolation this filter exists to provide. Not fixed here: a naive
 * X-Forwarded-For trust would let any direct caller spoof a different header value per request and
 * bypass the limiter entirely, which is a worse regression than the current gap - a correct fix
 * needs an explicit trusted-proxy allowlist, tracked as a follow-up (see #48's own "deployment
 * shape" note and the PR #59 review that flagged this).
 *
 * <p>One in-memory token bucket per (rule, source IP) pair, via bucket4j - sufficient for this
 * project's current single-instance self-hosted deployment target (see #48's own open question;
 * revisit with a shared/Redis-backed bucket store if multi-instance deployment ever becomes a
 * goal). {@link #evictStaleBuckets()} bounds the map's memory growth for a fixed population of
 * callers, but has no hard cap on total distinct entries between sweeps - also tracked as a
 * follow-up, since a caller with many source IPs (e.g. a routed IPv6 block) could otherwise grow it
 * significantly before the next sweep.
 */
@Component
public class RateLimitFilter extends OncePerRequestFilter {

  private static final AntPathMatcher PATH_MATCHER = new AntPathMatcher();
  private static final UrlPathHelper URL_PATH_HELPER = UrlPathHelper.defaultInstance;

  // Generous relative to every configured refill-period (all far under an hour in practice) -
  // this never evicts a bucket that's still actively limiting someone, only ones truly idle.
  private static final Duration STALE_AFTER = Duration.ofHours(1);

  private final Map<String, TrackedBucket> buckets = new ConcurrentHashMap<>();
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
    String key = rule.name() + ':' + sourceIp;
    TrackedBucket existing = buckets.get(key);
    if (existing != null) {
      existing.touch();
      return existing.getBucket();
    }
    // Racing the very first request for a brand-new key is harmless: at most one of the two
    // freshly-built buckets is kept (putIfAbsent), the other discarded unused - never touched, so
    // never mistaken for state that needs preserving.
    TrackedBucket created = new TrackedBucket(newBucket(rule.config()));
    TrackedBucket winner = buckets.putIfAbsent(key, created);
    return (winner != null ? winner : created).getBucket();
  }

  private Bucket newBucket(RateLimitProperties.Rule config) {
    Bandwidth limit =
        Bandwidth.builder()
            .capacity(config.capacity())
            .refillGreedy(config.capacity(), config.refillPeriod())
            .build();
    return Bucket.builder().addLimit(limit).build();
  }

  @Scheduled(fixedDelay = 30, timeUnit = TimeUnit.MINUTES)
  void evictStaleBuckets() {
    Instant cutoff = Instant.now().minus(STALE_AFTER);
    buckets.values().removeIf(tracked -> tracked.getLastAccess().isBefore(cutoff));
  }

  private record Rule(String name, RateLimitProperties.Rule config) {}

  private static final class TrackedBucket {
    private final Bucket bucket;
    private volatile Instant lastAccess = Instant.now();

    TrackedBucket(Bucket bucket) {
      this.bucket = bucket;
    }

    Bucket getBucket() {
      return bucket;
    }

    void touch() {
      lastAccess = Instant.now();
    }

    Instant getLastAccess() {
      return lastAccess;
    }
  }
}
