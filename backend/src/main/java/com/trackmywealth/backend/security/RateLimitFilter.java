package com.trackmywealth.backend.security;

import com.trackmywealth.backend.config.RateLimitProperties;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
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

/**
 * FR-AUT-010 (#48): per-source (IP) request limits on authentication-adjacent endpoints,
 * independent of {@code app_user}'s own per-account lockout (US-02-02's {@code
 * failed_login_count}/{@code locked_until}) - without this, an attacker sprays low-and-slow guesses
 * across many different accounts from one IP without ever tripping any single account's lockout.
 *
 * <p>Covers {@code POST /api/v1/auth/login}, {@code POST /api/v1/auth/refresh}, {@code POST
 * /api/v1/setup/administrator} (unauthenticated and brute-forceable until first use), and {@code
 * POST /api/v1/sessions/{id}/revoke} (authenticated, but issue #57 noted a caller can already
 * cheaply hammer it - each denial doubles DB connection usage via {@code
 * AuthorizationDenialAuditService}'s {@code REQUIRES_NEW} write).
 *
 * <p>Runs before {@link JwtAuthenticationFilter} in {@code SecurityConfig} - an excess request is
 * rejected before it costs a JWT parse or an {@code AppUserRepository.findAuthSnapshot} query, not
 * just before the endpoint's own business logic.
 *
 * <p>One in-memory token bucket per (rule, source IP) pair, via bucket4j - sufficient for this
 * project's current single-instance self-hosted deployment target (see #48's own open question;
 * revisit with a shared/Redis-backed bucket store if multi-instance deployment ever becomes a
 * goal). {@link #evictStaleBuckets()} bounds the map's memory growth, since a distinct entry is
 * created for every source IP that has ever called a rate-limited endpoint.
 */
@Component
public class RateLimitFilter extends OncePerRequestFilter {

  private static final AntPathMatcher PATH_MATCHER = new AntPathMatcher();
  private static final String LOGIN_PATH = "/api/v1/auth/login";
  private static final String REFRESH_PATH = "/api/v1/auth/refresh";
  private static final String SETUP_PATH = "/api/v1/setup/administrator";
  private static final String SESSION_REVOKE_PATH_PATTERN = "/api/v1/sessions/*/revoke";

  // Generous relative to every configured refill-period (all far under an hour in practice) -
  // this never evicts a bucket that's still actively limiting someone, only ones truly idle.
  private static final Duration STALE_AFTER = Duration.ofHours(1);

  private final RateLimitProperties properties;
  private final Map<String, TrackedBucket> buckets = new ConcurrentHashMap<>();

  public RateLimitFilter(RateLimitProperties properties) {
    this.properties = properties;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
      throws ServletException, IOException {
    if (!properties.enabled()) {
      filterChain.doFilter(request, response);
      return;
    }

    Optional<Rule> rule = ruleFor(request.getMethod(), request.getRequestURI());
    if (rule.isEmpty()) {
      filterChain.doFilter(request, response);
      return;
    }

    Bucket bucket = bucketFor(rule.get(), request.getRemoteAddr());
    if (bucket.tryConsume(1)) {
      filterChain.doFilter(request, response);
      return;
    }

    response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
    response.setHeader(
        "Retry-After", String.valueOf(rule.get().config().refillPeriod().toSeconds()));
  }

  private Optional<Rule> ruleFor(String method, String path) {
    if (!"POST".equals(method)) {
      return Optional.empty();
    }
    if (LOGIN_PATH.equals(path)) {
      return Optional.of(new Rule("login", properties.login()));
    }
    if (REFRESH_PATH.equals(path)) {
      return Optional.of(new Rule("refresh", properties.refresh()));
    }
    if (SETUP_PATH.equals(path)) {
      return Optional.of(new Rule("setup", properties.setup()));
    }
    if (PATH_MATCHER.match(SESSION_REVOKE_PATH_PATTERN, path)) {
      return Optional.of(new Rule("sessionRevoke", properties.sessionRevoke()));
    }
    return Optional.empty();
  }

  private Bucket bucketFor(Rule rule, String sourceIp) {
    String key = rule.name() + ':' + sourceIp;
    TrackedBucket tracked =
        buckets.computeIfAbsent(key, unused -> new TrackedBucket(newBucket(rule.config())));
    tracked.touch();
    return tracked.getBucket();
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
