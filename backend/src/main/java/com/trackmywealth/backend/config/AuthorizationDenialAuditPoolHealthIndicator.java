package com.trackmywealth.backend.config;

import java.sql.Connection;
import org.springframework.boot.health.contributor.AbstractHealthIndicator;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.Status;
import org.springframework.stereotype.Component;

/**
 * Health of the denial-audit pool (#205), reported as {@code authorizationDenialAuditPool}.
 *
 * <p>Spring Boot only checks {@code DataSource} beans, and {@link AuthorizationDenialAuditPool} is
 * deliberately not one. Every audited denial fails while this pool cannot hand out a working
 * connection, so it gets the same validity check Boot runs against the main pool.
 */
@Component
public class AuthorizationDenialAuditPoolHealthIndicator extends AbstractHealthIndicator {

  private static final int VALIDATION_TIMEOUT_SECONDS = 1;

  private final AuthorizationDenialAuditPool pool;

  public AuthorizationDenialAuditPoolHealthIndicator(AuthorizationDenialAuditPool pool) {
    super("Denial-audit pool health check failed");
    this.pool = pool;
  }

  @Override
  protected void doHealthCheck(Health.Builder builder) throws Exception {
    try (Connection connection = pool.getConnection()) {
      builder
          .status(connection.isValid(VALIDATION_TIMEOUT_SECONDS) ? Status.UP : Status.DOWN)
          .withDetail("pool", AuthorizationDenialAuditPool.POOL_NAME);
    }
  }
}
