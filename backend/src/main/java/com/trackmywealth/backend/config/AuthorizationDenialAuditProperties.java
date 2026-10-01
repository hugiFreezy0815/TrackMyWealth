package com.trackmywealth.backend.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Bounds the cost and retention of object-level authorization-denial auditing (#205).
 *
 * <p>Exact denial rows are rate-limited per principal. Once the budget is exhausted, one summary
 * row is written per refill window and further rows are suppressed until capacity returns; how many
 * were suppressed is recorded once the window closes. Every row that is written is written
 * synchronously through a small connection pool of its own ({@code auditPoolSize}), never the
 * request's pool, so a denied request can neither starve the main pool nor lose its audit row.
 *
 * <p>The budget is held in memory, so every bound here applies per application instance.
 */
@ConfigurationProperties(prefix = "app.authorization-denial-audit")
public record AuthorizationDenialAuditProperties(
    int maxWritesPerPrincipal,
    Duration refillPeriod,
    int maxPrincipals,
    int auditPoolSize,
    Duration auditConnectionTimeout,
    Duration auditStatementTimeout,
    Duration retention,
    Duration cleanupInterval,
    int cleanupBatchSize) {

  private static final int MIN_COUNT = 1;
  private static final Duration MIN_STATEMENT_TIMEOUT = Duration.ofSeconds(1);
  private static final String PREFIX = "app.authorization-denial-audit.";

  public AuthorizationDenialAuditProperties {
    requireAtLeastOne(maxWritesPerPrincipal, "max-writes-per-principal");
    requirePositive(refillPeriod, "refill-period");
    requireAtLeastOne(maxPrincipals, "max-principals");
    requireAtLeastOne(auditPoolSize, "audit-pool-size");
    requirePositive(auditConnectionTimeout, "audit-connection-timeout");
    requirePositive(auditStatementTimeout, "audit-statement-timeout");
    // JDBC's Statement#setQueryTimeout counts whole seconds; anything shorter would mean "none".
    if (auditStatementTimeout.compareTo(MIN_STATEMENT_TIMEOUT) < 0) {
      throw new IllegalArgumentException(PREFIX + "audit-statement-timeout must be >= 1s");
    }
    requirePositive(retention, "retention");
    requirePositive(cleanupInterval, "cleanup-interval");
    requireAtLeastOne(cleanupBatchSize, "cleanup-batch-size");
  }

  /** {@link #auditStatementTimeout()} rounded up to the whole seconds JDBC works in. */
  public int auditStatementTimeoutSeconds() {
    return Math.toIntExact(auditStatementTimeout.plusNanos(999_999_999L).toSeconds());
  }

  private static void requireAtLeastOne(int value, String name) {
    if (value < MIN_COUNT) {
      throw new IllegalArgumentException(PREFIX + name + " must be >= " + MIN_COUNT);
    }
  }

  private static void requirePositive(Duration value, String name) {
    if (value == null || value.isZero() || value.isNegative()) {
      throw new IllegalArgumentException(PREFIX + name + " must be positive");
    }
  }
}
