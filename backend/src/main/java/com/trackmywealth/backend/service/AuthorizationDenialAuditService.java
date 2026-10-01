package com.trackmywealth.backend.service;

import com.trackmywealth.backend.entity.AuthorizationDenialLog;
import com.trackmywealth.backend.repository.AuthorizationDenialLogRepository;
import com.trackmywealth.backend.security.AuthenticatedUserPrincipal;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * US-28-02 (FR-TEN-004/006): the single place every single-resource read/write endpoint routes
 * through when a caller-supplied id doesn't resolve to a row the caller is entitled to - whether
 * because it genuinely doesn't exist or because it belongs to a different tenant/user. Both cases
 * are logged identically and produce the identical 404, so neither a caller nor this audit trail
 * can ever distinguish "not yours" from "doesn't exist."
 */
@Service
public class AuthorizationDenialAuditService {

  public static final String GENERIC_NOT_FOUND_DETAIL = "Not found.";

  private final AuthorizationDenialLogRepository repository;

  public AuthorizationDenialAuditService(AuthorizationDenialLogRepository repository) {
    this.repository = repository;
  }

  /**
   * Runs in its own transaction, deliberately: the caller always throws the returned exception
   * immediately afterward, which rolls back the caller's own (typically already-open) transaction -
   * without {@code REQUIRES_NEW}, that rollback would take this audit row down with it, silently
   * losing the very denial it exists to record.
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public ResponseStatusException denyAsNotFound(
      AuthenticatedUserPrincipal actor, String requestedEntityType, UUID requestedEntityId) {
    return recordDenial(actor.userId(), requestedEntityType, requestedEntityId);
  }

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public ResponseStatusException denyAsNotFound(
      UUID principalUserId, String requestedEntityType, UUID requestedEntityId) {
    return recordDenial(principalUserId, requestedEntityType, requestedEntityId);
  }

  private ResponseStatusException recordDenial(
      UUID principalUserId, String requestedEntityType, UUID requestedEntityId) {
    AuthorizationDenialLog log = new AuthorizationDenialLog();
    log.setPrincipalUserId(principalUserId);
    log.setRequestedEntityType(requestedEntityType);
    log.setRequestedEntityId(requestedEntityId);
    log.setReason("NOT_FOUND");
    repository.saveAndFlush(log);
    return new ResponseStatusException(HttpStatus.NOT_FOUND, GENERIC_NOT_FOUND_DETAIL);
  }
}
