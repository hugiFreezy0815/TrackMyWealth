package com.trackmywealth.backend.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.trackmywealth.backend.config.AuthorizationDenialAuditConfig;
import com.trackmywealth.backend.config.AuthorizationDenialAuditProperties;
import com.trackmywealth.backend.security.AuthenticatedUserPrincipal;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.LongAdder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * US-28-02 / #205: the single audited, non-enumerating object-denial path.
 *
 * <p>The caller always receives the same generic 404 immediately. Audit persistence is offered to
 * a dedicated bounded executor and never runs on the request thread, so a denied request cannot
 * hold its business-transaction connection while waiting for a second connection. This removes the
 * {@code REQUIRES_NEW} pool-starvation pattern without weakening rollback independence: the writer
 * starts its own transaction on its own background thread.
 *
 * <p>To bound a signed-in caller's ability to grow {@code authorization_denial_log}, exact rows are
 * capped per principal and refill window. The first suppressed denial in a window emits one
 * {@code RATE_LIMITED} summary row; subsequent denials in that window emit no row. Response status,
 * detail and timing path are otherwise identical, so throttling does not become an enumeration
 * signal.
 */
@Service
public class AuthorizationDenialAuditService {

  public static final String GENERIC_NOT_FOUND_DETAIL = "Not found.";

  private static final Logger LOG = LoggerFactory.getLogger(AuthorizationDenialAuditService.class);

  private final Executor auditExecutor;
  private final AuthorizationDenialAuditWriterService writer;
  private final Cache<UUID, DenialWindow> principalWindows;
  private final int maxWritesPerPrincipal;
  private final long refillNanos;
  private final LongAdder rejectedAuditTasks = new LongAdder();
  private final LongAdder failedAuditWrites = new LongAdder();

  public AuthorizationDenialAuditService(
      @Qualifier(AuthorizationDenialAuditConfig.EXECUTOR_BEAN) Executor auditExecutor,
      AuthorizationDenialAuditWriterService writer,
      AuthorizationDenialAuditProperties properties) {
    this.auditExecutor = auditExecutor;
    this.writer = writer;
    this.maxWritesPerPrincipal = properties.maxWritesPerPrincipal();
    this.refillNanos = properties.refillPeriod().toNanos();
    Duration staleAfter = properties.refillPeriod().multipliedBy(2);
    this.principalWindows =
        Caffeine.newBuilder()
            .maximumSize(properties.maxPrincipals())
            .expireAfterAccess(staleAfter)
            .build();
  }

  public ResponseStatusException denyAsNotFound(
      AuthenticatedUserPrincipal actor, String requestedEntityType, UUID requestedEntityId) {
    return denyAsNotFound(actor.userId(), requestedEntityType, requestedEntityId);
  }

  public ResponseStatusException denyAsNotFound(
      UUID principalUserId, String requestedEntityType, UUID requestedEntityId) {
    AuditDecision decision =
        principalWindows
            .get(principalUserId, ignored -> new DenialWindow(System.nanoTime()))
            .decide(System.nanoTime(), refillNanos, maxWritesPerPrincipal);

    if (decision == AuditDecision.EXACT) {
      submit(
          () -> writer.recordDenial(principalUserId, requestedEntityType, requestedEntityId));
    } else if (decision == AuditDecision.SUMMARY) {
      submit(() -> writer.recordRateLimitedSummary(principalUserId));
    }

    return new ResponseStatusException(HttpStatus.NOT_FOUND, GENERIC_NOT_FOUND_DETAIL);
  }

  private void submit(Runnable auditWrite) {
    try {
      auditExecutor.execute(
          () -> {
            try {
              auditWrite.run();
            } catch (RuntimeException ex) {
              failedAuditWrites.increment();
              long failed = failedAuditWrites.sum();
              if (failed == 1 || failed % 100 == 0) {
                LOG.error(
                    "Authorization-denial audit persistence failed {} time(s); "
                        + "request responses remain fail-closed/non-enumerating.",
                    failed,
                    ex);
              }
            }
          });
    } catch (RejectedExecutionException ex) {
      rejectedAuditTasks.increment();
      long rejected = rejectedAuditTasks.sum();
      if (rejected == 1 || rejected % 100 == 0) {
        LOG.warn(
            "Authorization-denial audit queue is full; dropped {} audit task(s). "
                + "Request responses remain fail-closed/non-enumerating.",
            rejected);
      }
    }
  }

  private enum AuditDecision {
    EXACT,
    SUMMARY,
    SUPPRESS
  }

  private static final class DenialWindow {

    private long startedAtNanos;
    private int exactWrites;
    private boolean summaryWritten;

    private DenialWindow(long startedAtNanos) {
      this.startedAtNanos = startedAtNanos;
    }

    private synchronized AuditDecision decide(
        long nowNanos, long refillNanos, int maxWritesPerPrincipal) {
      if (nowNanos - startedAtNanos >= refillNanos) {
        startedAtNanos = nowNanos;
        exactWrites = 0;
        summaryWritten = false;
      }

      if (exactWrites < maxWritesPerPrincipal) {
        exactWrites++;
        return AuditDecision.EXACT;
      }
      if (!summaryWritten) {
        summaryWritten = true;
        return AuditDecision.SUMMARY;
      }
      return AuditDecision.SUPPRESS;
    }
  }
}
