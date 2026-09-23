package com.trackmywealth.backend.security;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.trackmywealth.backend.config.RateLimitProperties;
import com.trackmywealth.backend.controller.AuthController;
import com.trackmywealth.backend.controller.MfaController;
import com.trackmywealth.backend.controller.SecurityController;
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
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;
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
 * SetupController#ADMINISTRATOR_PATH} (unauthenticated and brute-forceable until first use), {@link
 * SessionController#REVOKE_PATH} (authenticated, but issue #57 noted a caller can already cheaply
 * hammer it - each denial doubles DB connection usage via {@code AuthorizationDenialAuditService}'s
 * {@code REQUIRES_NEW} write), {@link AuthController#MFA_VERIFY_PATH} (unauthenticated, and a
 * 6-digit TOTP code is brute-forceable without a limit here independent of the challenge token's
 * own short expiry), and {@link MfaController#CONFIRM_PATH} (authenticated, but the same
 * brute-forceable 6-digit-code reasoning applies to confirming a pending enrollment). Referencing
 * each controller's own constant, rather than a re-typed literal, means the two can never silently
 * drift apart the way a second hardcoded copy of the same path could.
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
 * header at all - and Tomcat's valve already implements the trusted-proxy check correctly. Trusting
 * a proxy this way also affects {@code TokenIssuanceService}'s hashed {@code
 * UserSession.ipAddressHash} (same {@code getRemoteAddr()} value, read independently by {@code
 * AuthController}/{@code SetupController}) - see {@code TRUSTED_PROXIES}' own documentation before
 * widening it.
 *
 * <p>IPv6 addresses are bucketed by their /64 network prefix, not the full 128-bit address ({@link
 * #sourceKey}) - an attacker with a routed IPv6 /64 (trivially obtainable from most ISP
 * allocations) could otherwise mint an unlimited number of fresh, full-capacity buckets by using a
 * different address within that /64 on every request. IPv4 addresses are used in full, since IPv4
 * allocations of that scale aren't handed to individual end users. The /64 prefix is parsed by hand
 * ({@link #ipv6Slash64Prefix}) rather than via {@code InetAddress.getByName} - the latter falls
 * back to a synchronous DNS lookup of the whole string for any input that isn't a valid IP literal,
 * and {@code RemoteIpValve} only validates that the *direct peer* is a trusted proxy, never that
 * the *forwarded value* it then trusts verbatim is actually a well-formed address. A hand parser
 * that returns null - falling back to the raw string as the bucket key - on anything it can't
 * confidently read as IPv6 keeps this filter's hot path free of any blocking call, regardless of
 * what a misconfigured or malicious upstream puts in the header.
 *
 * <p>One in-memory token bucket per (rule, source key) pair, via bucket4j - sufficient for this
 * project's current single-instance self-hosted deployment target (see #48's own open question;
 * revisit with a shared/Redis-backed bucket store if multi-instance deployment ever becomes a
 * goal). Each {@link Rule} owns its own bounded Caffeine cache ({@code app.rate-limit.max-buckets},
 * split evenly across every rule, see {@link #RULE_COUNT}) rather than all of them sharing one -
 * otherwise a burst against one endpoint could evict another, unrelated endpoint's still-active
 * buckets purely on cache pressure. Caffeine's size-based eviction is frequency-aware (not plain
 * LRU), so a bucket a caller keeps actively consuming from is unlikely to be the one it picks when
 * the cap is reached, but eviction here is a memory-bound safety valve, not a guarantee that only
 * truly idle entries are ever reclaimed - the time-based {@link #STALE_AFTER} expiry is what that
 * guarantee actually rests on, for populations that stay under the cap.
 */
@Component
public class RateLimitFilter extends OncePerRequestFilter {

  private static final AntPathMatcher PATH_MATCHER = new AntPathMatcher();
  private static final UrlPathHelper URL_PATH_HELPER = UrlPathHelper.defaultInstance;
  private static final Pattern HEX_GROUP = Pattern.compile("^[0-9a-fA-F]{1,4}$");

  // Generous relative to every configured refill-period (all far under an hour in practice) -
  // under the per-rule cache's size cap, this is what actually guarantees a bucket is only
  // reclaimed once truly idle (see the class javadoc's note on size-based eviction).
  private static final Duration STALE_AFTER = Duration.ofHours(1);

  // The divisor perRuleMaxBuckets below splits app.rate-limit.max-buckets across - keep in sync
  // with the number of *Rule fields/newRule(...) calls in the constructor.
  private static final int RULE_COUNT = 7;

  private final boolean enabled;
  private final Rule loginRule;
  private final Rule refreshRule;
  private final Rule setupRule;
  private final Rule sessionRevokeRule;
  private final Rule mfaVerifyRule;
  private final Rule mfaConfirmRule;
  private final Rule securityCreateRule;

  public RateLimitFilter(RateLimitProperties properties) {
    this.enabled = properties.enabled();
    // Split (not duplicated) across every rule, so app.rate-limit.max-buckets still bounds total
    // worst-case memory across all of them combined, matching its own documented meaning.
    int perRuleMaxBuckets = Math.max(1, properties.maxBuckets() / RULE_COUNT);
    this.loginRule = newRule("login", properties.login(), perRuleMaxBuckets);
    this.refreshRule = newRule("refresh", properties.refresh(), perRuleMaxBuckets);
    this.setupRule = newRule("setup", properties.setup(), perRuleMaxBuckets);
    this.sessionRevokeRule =
        newRule("sessionRevoke", properties.sessionRevoke(), perRuleMaxBuckets);
    this.mfaVerifyRule = newRule("mfaVerify", properties.mfaVerify(), perRuleMaxBuckets);
    this.mfaConfirmRule = newRule("mfaConfirm", properties.mfaConfirm(), perRuleMaxBuckets);
    this.securityCreateRule =
        newRule("securityCreate", properties.securityCreate(), perRuleMaxBuckets);
  }

  private static Rule newRule(String name, RateLimitProperties.Rule config, int maxBuckets) {
    Cache<String, Bucket> buckets =
        Caffeine.newBuilder().maximumSize(maxBuckets).expireAfterAccess(STALE_AFTER).build();
    return new Rule(name, config, buckets);
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
    if (AuthController.MFA_VERIFY_PATH.equals(path)) {
      return Optional.of(mfaVerifyRule);
    }
    if (MfaController.CONFIRM_PATH.equals(path)) {
      return Optional.of(mfaConfirmRule);
    }
    // US-12-01: every insert into the shared security master is visible to every tenant, so an
    // authenticated member must not be able to flood it. Like the rules above, keyed by source.
    if (SecurityController.BASE_PATH.equals(path)) {
      return Optional.of(securityCreateRule);
    }
    return Optional.empty();
  }

  private Bucket bucketFor(Rule rule, String sourceIp) {
    // Each Rule has its own cache, so the key only needs to distinguish sources within it - no
    // rule-name prefix needed the way a single shared cache across all rules would have required.
    return rule.buckets().get(sourceKey(sourceIp), key -> newBucket(rule.config()));
  }

  // IPv6: collapse to the /64 network prefix so an attacker rotating through a single routed
  // /64 can't mint an unlimited number of fresh buckets, one per distinct address. IPv4 (or
  // anything else - garbage, an unparseable forwarded value) is used as-is.
  // The /64 prefix is the address's first four 16-bit groups.
  private static final int PREFIX_GROUP_COUNT = 4;

  private static String sourceKey(String remoteAddr) {
    if (remoteAddr.indexOf(':') < 0) {
      return remoteAddr;
    }
    return ipv6Slash64Prefix(remoteAddr).map(HexFormat.of()::formatHex).orElse(remoteAddr);
  }

  // Parses an IPv6 literal's /64 prefix directly from text, entirely by hand - no
  // java.net.InetAddress involved, so there is no path here that can ever block on a DNS lookup
  // (see the class javadoc). Empty for anything not confidently read as IPv6, which sourceKey()
  // then falls back to using as an opaque string - the same safe degradation an unrecognized
  // address always had.
  private static Optional<byte[]> ipv6Slash64Prefix(String address) {
    int compressAt = address.indexOf("::");
    if (compressAt != address.lastIndexOf("::")) {
      return Optional.empty(); // more than one "::" is never valid
    }
    List<String> left;
    List<String> right;
    if (compressAt == -1) {
      left = splitGroups(address);
      right = List.of();
    } else {
      left = splitGroups(address.substring(0, compressAt));
      right = splitGroups(address.substring(compressAt + 2));
    }
    int missing = 8 - left.size() - right.size();
    // No "::" present must supply exactly 8 groups; "::" must stand in for at least one.
    if (compressAt == -1 ? missing != 0 : missing < 1) {
      return Optional.empty();
    }

    byte[] prefix = new byte[8];
    int written = 0;
    for (String group : left) {
      if (written == PREFIX_GROUP_COUNT) {
        return Optional.of(prefix); // the /64 prefix is already fully determined
      }
      if (!HEX_GROUP.matcher(group).matches()) {
        return Optional.empty();
      }
      writeGroup(prefix, written, Integer.parseInt(group, 16));
      written++;
    }
    // Every group "::" stands in for is zero, which the array already defaults to - only
    // advancing past them (not writing anything) is needed.
    written = Math.min(PREFIX_GROUP_COUNT, written + missing);
    for (String group : right) {
      if (written == PREFIX_GROUP_COUNT) {
        return Optional.of(prefix);
      }
      if (!HEX_GROUP.matcher(group).matches()) {
        return Optional.empty();
      }
      writeGroup(prefix, written, Integer.parseInt(group, 16));
      written++;
    }
    return written == PREFIX_GROUP_COUNT ? Optional.of(prefix) : Optional.empty();
  }

  private static List<String> splitGroups(String part) {
    return part.isEmpty() ? List.of() : List.of(part.split(":"));
  }

  private static void writeGroup(byte[] dest, int groupIndex, int value) {
    dest[groupIndex * 2] = (byte) (value >>> 8);
    dest[groupIndex * 2 + 1] = (byte) value;
  }

  private Bucket newBucket(RateLimitProperties.Rule config) {
    Bandwidth limit =
        Bandwidth.builder()
            .capacity(config.capacity())
            .refillGreedy(config.capacity(), config.refillPeriod())
            .build();
    return Bucket.builder().addLimit(limit).build();
  }

  private record Rule(
      String name, RateLimitProperties.Rule config, Cache<String, Bucket> buckets) {}
}
