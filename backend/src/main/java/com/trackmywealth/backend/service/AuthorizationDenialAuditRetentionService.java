package com.trackmywealth.backend.service;

import com.trackmywealth.backend.config.AuthorizationDenialAuditProperties;
import com.trackmywealth.backend.repository.AuthorizationDenialLogRepository;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Deletes expired authorization-denial audit rows (#205).
 *
 * <p>The table is operational/security evidence, not immutable financial history. Its configurable
 * retention keeps incident review useful while preventing indefinite growth from normal mistakes
 * and hostile probing.
 */
@Service
public class AuthorizationDenialAuditRetentionService {

  private static final Logger LOG =
      LoggerFactory.getLogger(AuthorizationDenialAuditRetentionService.class);

  private final AuthorizationDenialLogRepository repository;
  private final AuthorizationDenialAuditProperties properties;

  public AuthorizationDenialAuditRetentionService(
      AuthorizationDenialLogRepository repository, AuthorizationDenialAuditProperties properties) {
    this.repository = repository;
    this.properties = properties;
  }

  @Scheduled(
      fixedDelayString = "${app.authorization-denial-audit.cleanup-interval:PT1H}",
      initialDelayString = "${app.authorization-denial-audit.cleanup-interval:PT1H}")
  @Transactional
  public int deleteExpiredRows() {
    OffsetDateTime cutoff = OffsetDateTime.now(ZoneOffset.UTC).minus(properties.retention());
    int deleted = repository.deleteOccurredBefore(cutoff);
    if (deleted > 0) {
      LOG.info("Deleted {} expired authorization-denial audit row(s)", deleted);
    }
    return deleted;
  }
}
