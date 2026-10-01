package com.trackmywealth.backend.service;

import com.trackmywealth.backend.config.AuthorizationDenialAuditProperties;
import com.trackmywealth.backend.repository.AuthorizationDenialLogRepository;
import java.time.Clock;
import java.time.OffsetDateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Deletes expired authorization-denial audit rows (#205).
 *
 * <p>The table is operational/security evidence, not immutable financial history. Its configurable
 * retention keeps incident review useful while preventing indefinite growth from normal mistakes
 * and hostile probing.
 *
 * <p>Rows are deleted in batches of {@code cleanup-batch-size}, each in its own short transaction,
 * so the first run against a large backlog never becomes one huge transaction with long-held locks
 * and a burst of WAL. Several instances running it at once is harmless: each batch deletes only
 * rows that are still there.
 */
@Service
public class AuthorizationDenialAuditRetentionService {

  private static final Logger LOG =
      LoggerFactory.getLogger(AuthorizationDenialAuditRetentionService.class);

  private final AuthorizationDenialLogRepository repository;
  private final AuthorizationDenialAuditProperties properties;
  private final TransactionTemplate transactionTemplate;
  private final Clock clock;

  public AuthorizationDenialAuditRetentionService(
      AuthorizationDenialLogRepository repository,
      AuthorizationDenialAuditProperties properties,
      PlatformTransactionManager transactionManager,
      Clock clock) {
    this.repository = repository;
    this.properties = properties;
    this.transactionTemplate = new TransactionTemplate(transactionManager);
    this.clock = clock;
  }

  @Scheduled(
      fixedDelayString = "${app.authorization-denial-audit.cleanup-interval:PT1H}",
      initialDelayString = "${app.authorization-denial-audit.cleanup-interval:PT1H}")
  public void deleteExpiredRows() {
    OffsetDateTime cutoff = OffsetDateTime.now(clock).minus(properties.retention());
    int batchSize = properties.cleanupBatchSize();
    long deleted = 0;
    int batchDeleted;
    do {
      Integer result =
          transactionTemplate.execute(
              status -> repository.deleteBatchOccurredBefore(cutoff, batchSize));
      batchDeleted = result == null ? 0 : result;
      deleted += batchDeleted;
    } while (batchDeleted == batchSize);
    if (deleted > 0) {
      LOG.info("Deleted {} expired authorization-denial audit row(s)", deleted);
    }
  }
}
