package com.trackmywealth.backend.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;

/** #205: misconfigured denial-audit bounds fail at startup, naming the offending property. */
class AuthorizationDenialAuditPropertiesTest {

  private static final Duration ONE_MINUTE = Duration.ofMinutes(1);
  private static final Duration ONE_SECOND = Duration.ofSeconds(1);

  @Test
  void validValuesAreAccepted() {
    AuthorizationDenialAuditProperties properties = valid(Duration.ofMillis(1500));

    assertThat(properties.auditStatementTimeoutSeconds())
        .as("rounded up to whole seconds, never down to 'no timeout'")
        .isEqualTo(2);
  }

  @Test
  void countsBelowOneAreRejected() {
    assertRejected(() -> withCounts(0, 1, 1, 1), "max-writes-per-principal");
    assertRejected(() -> withCounts(1, 0, 1, 1), "max-principals");
    assertRejected(() -> withCounts(1, 1, 0, 1), "audit-pool-size");
    assertRejected(() -> withCounts(1, 1, 1, 0), "cleanup-batch-size");
  }

  @Test
  void missingZeroOrNegativeDurationsAreRejected() {
    for (Duration bad : new Duration[] {null, Duration.ZERO, Duration.ofSeconds(-1)}) {
      assertRejected(() -> withDurations(bad, ONE_SECOND, ONE_SECOND, ONE_MINUTE), "refill-period");
      assertRejected(
          () -> withDurations(ONE_MINUTE, bad, ONE_SECOND, ONE_MINUTE), "audit-connection-timeout");
      assertRejected(
          () -> withDurations(ONE_MINUTE, ONE_SECOND, bad, ONE_MINUTE), "audit-statement-timeout");
      assertRejected(() -> withDurations(ONE_MINUTE, ONE_SECOND, ONE_SECOND, bad), "retention");
    }
  }

  @Test
  void aStatementTimeoutBelowOneSecondIsRejected() {
    assertRejected(() -> valid(Duration.ofMillis(999)), "audit-statement-timeout must be >= 1s");
  }

  private static AuthorizationDenialAuditProperties valid(Duration statementTimeout) {
    return new AuthorizationDenialAuditProperties(
        200, ONE_MINUTE, 100, 2, ONE_SECOND, statementTimeout, ONE_MINUTE, ONE_MINUTE, 100);
  }

  private static AuthorizationDenialAuditProperties withCounts(
      int maxWrites, int maxPrincipals, int poolSize, int batchSize) {
    return new AuthorizationDenialAuditProperties(
        maxWrites,
        ONE_MINUTE,
        maxPrincipals,
        poolSize,
        ONE_SECOND,
        ONE_SECOND,
        ONE_MINUTE,
        ONE_MINUTE,
        batchSize);
  }

  private static AuthorizationDenialAuditProperties withDurations(
      Duration refill, Duration connectionTimeout, Duration statementTimeout, Duration retention) {
    return new AuthorizationDenialAuditProperties(
        200, refill, 100, 2, connectionTimeout, statementTimeout, retention, ONE_MINUTE, 100);
  }

  private static void assertRejected(Runnable construction, String message) {
    assertThatThrownBy(construction::run)
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining(message);
  }
}
