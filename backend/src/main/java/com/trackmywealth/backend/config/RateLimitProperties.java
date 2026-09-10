package com.trackmywealth.backend.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Binds {@code app.rate-limit.*} (application.yml) - FR-AUT-010 (#48): per-source (IP) request
 * limits on authentication-adjacent endpoints. {@code capacity} tokens refill every {@code
 * refill-period}, independently per endpoint group. See {@link
 * com.trackmywealth.backend.security.RateLimitFilter} for how these are applied.
 *
 * <p>{@code maxBuckets} (#60) caps the total number of distinct (rule, source) buckets held in
 * memory at once, across all four rules combined - without it, a caller spread across many source
 * addresses (e.g. a routed IPv6 block) could grow the bucket store unboundedly between eviction
 * sweeps. Split evenly into an independent cache per rule (see {@code RateLimitFilter.Rule}), not
 * one cache shared by all four, so a burst against one endpoint can't evict another's buckets.
 */
@ConfigurationProperties(prefix = "app.rate-limit")
public record RateLimitProperties(
    boolean enabled, int maxBuckets, Rule login, Rule refresh, Rule setup, Rule sessionRevoke) {

  public record Rule(int capacity, Duration refillPeriod) {}
}
