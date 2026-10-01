package com.trackmywealth.backend.service;

import com.trackmywealth.backend.entity.AuthorizationDenialLog;
import com.trackmywealth.backend.repository.AuthorizationDenialLogRepository;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Persists authorization-denial audit rows on a background thread (#205).
 *
 * <p>The request transaction is never joined: callers invoke this service only from the dedicated
 * audit executor, so the write owns a normal transaction/connection of its own and survives the
 * request's rollback without requiring a nested {@code REQUIRES_NEW} connection while the request
 * is still holding one.
 */
@Service
public class AuthorizationDenialAuditWriterService {

  public static final String REASON_NOT_FOUND = "NOT_FOUND";
  public static final String REASON_RATE_LIMITED = "RATE_LIMITED";
  public static final String SUMMARY_ENTITY_TYPE = "AuthorizationDenial";

  private final AuthorizationDenialLogRepository repository;

  public AuthorizationDenialAuditWriterService(AuthorizationDenialLogRepository repository) {
    this.repository = repository;
  }

  @Transactional
  public void recordDenial(
      UUID principalUserId, String requestedEntityType, UUID requestedEntityId) {
    persist(principalUserId, requestedEntityType, requestedEntityId, REASON_NOT_FOUND);
  }

  @Transactional
  public void recordRateLimitedSummary(UUID principalUserId) {
    persist(principalUserId, SUMMARY_ENTITY_TYPE, null, REASON_RATE_LIMITED);
  }

  private void persist(
      UUID principalUserId, String requestedEntityType, UUID requestedEntityId, String reason) {
    AuthorizationDenialLog log = new AuthorizationDenialLog();
    log.setPrincipalUserId(principalUserId);
    log.setRequestedEntityType(requestedEntityType);
    log.setRequestedEntityId(requestedEntityId);
    log.setReason(reason);
    repository.save(log);
  }
}
