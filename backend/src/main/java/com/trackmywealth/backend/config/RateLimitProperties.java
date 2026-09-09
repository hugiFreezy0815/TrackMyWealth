package com.trackmywealth.backend.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Binds {@code app.rate-limit.*} (application.yml) - FR-AUT-010 (#48): per-source (IP) request
 * limits on authentication-adjacent endpoints. {@code capacity} tokens refill every {@code
 * refill-period}, independently per endpoint group. See {@link
 * com.trackmywealth.backend.security.RateLimitFilter} for how these are applied.
 */
@ConfigurationProperties(prefix = "app.rate-limit")
public record RateLimitProperties(
    boolean enabled, Rule login, Rule refresh, Rule setup, Rule sessionRevoke) {

  public record Rule(int capacity, Duration refillPeriod) {}
}
