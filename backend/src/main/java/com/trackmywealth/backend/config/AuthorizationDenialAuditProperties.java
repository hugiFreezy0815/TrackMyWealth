package com.trackmywealth.backend.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Bounds the cost and retention of object-level authorization-denial auditing (#205).
 *
 * <p>Exact denial rows are rate-limited per principal. Once the budget is exhausted, one summary
 * row is written per refill window and further rows are suppressed until capacity returns. Writes
 * use a dedicated bounded executor so a denied request never waits for a second JDBC connection.
 */
@ConfigurationProperties(prefix = "app.authorization-denial-audit")
public record AuthorizationDenialAuditProperties(
    int maxWritesPerPrincipal,
    Duration refillPeriod,
    int maxPrincipals,
    int queueCapacity,
    int writerThreads,
    Duration retention,
    Duration cleanupInterval) {

  public AuthorizationDenialAuditProperties {
    if (maxWritesPerPrincipal < 1) {
      throw new IllegalArgumentException(
          "app.authorization-denial-audit.max-writes-per-principal must be >= 1");
    }
    if (refillPeriod == null || refillPeriod.isZero() || refillPeriod.isNegative()) {
      throw new IllegalArgumentException(
          "app.authorization-denial-audit.refill-period must be positive");
    }
    if (maxPrincipals < 1) {
      throw new IllegalArgumentException(
          "app.authorization-denial-audit.max-principals must be >= 1");
    }
    if (queueCapacity < 1) {
      throw new IllegalArgumentException(
          "app.authorization-denial-audit.queue-capacity must be >= 1");
    }
    if (writerThreads < 1) {
      throw new IllegalArgumentException(
          "app.authorization-denial-audit.writer-threads must be >= 1");
    }
    if (retention == null || retention.isZero() || retention.isNegative()) {
      throw new IllegalArgumentException("app.authorization-denial-audit.retention must be positive");
    }
    if (cleanupInterval == null || cleanupInterval.isZero() || cleanupInterval.isNegative()) {
      throw new IllegalArgumentException(
          "app.authorization-denial-audit.cleanup-interval must be positive");
    }
  }
}
