package com.trackmywealth.backend.service;

import com.trackmywealth.backend.config.AuthorizationDenialAuditProperties;
import com.trackmywealth.backend.repository.AuthorizationDenialAuditWriteRepository;
import com.trackmywealth.backend.security.AuthenticatedUserPrincipal;
import com.trackmywealth.backend.security.DenialAuditBudget;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * US-28-02 / #205: the single audited, non-enumerating object-denial path.
 *
 * <p>Every single-resource endpoint routes here when a caller-supplied id doesn't resolve to a row
 * the caller is entitled to, whether it doesn't exist or belongs to someone else. Both are recorded
 * identically and answered with the identical 404.
 *
 * <p>The row is written synchronously, before the 404 is returned, through {@link
 * AuthorizationDenialAuditWriteRepository}'s own connection pool: it is never lost, survives the
 * request's rollback, and never competes with the request for a main-pool connection. If it cannot
 * be written, the request fails instead of continuing without its audit row.
 *
 * <p>{@link DenialAuditBudget} bounds how many rows one principal can cause per window: exact rows
 * up to the budget, then one {@code RATE_LIMITED} summary, then none until the window refills. The
 * response is the same whichever row, if any, is written, so the budget is no enumeration signal.
 */
@Service
public class AuthorizationDenialAuditService {

  public static final String GENERIC_NOT_FOUND_DETAIL = "Not found.";
  public static final String REASON_NOT_FOUND = "NOT_FOUND";
  public static final String REASON_RATE_LIMITED = "RATE_LIMITED";
  public static final String SUMMARY_ENTITY_TYPE = "AuthorizationDenial";

  private final AuthorizationDenialAuditWriteRepository writeRepository;
  private final DenialAuditBudget budget;

  @Autowired
  public AuthorizationDenialAuditService(
      AuthorizationDenialAuditWriteRepository writeRepository,
      AuthorizationDenialAuditProperties properties) {
    this(
        writeRepository,
        new DenialAuditBudget(
            properties.maxWritesPerPrincipal(),
            properties.refillPeriod(),
            properties.maxPrincipals(),
            System::nanoTime));
  }

  AuthorizationDenialAuditService(
      AuthorizationDenialAuditWriteRepository writeRepository, DenialAuditBudget budget) {
    this.writeRepository = writeRepository;
    this.budget = budget;
  }

  public ResponseStatusException denyAsNotFound(
      AuthenticatedUserPrincipal actor, String requestedEntityType, UUID requestedEntityId) {
    return denyAsNotFound(actor.userId(), requestedEntityType, requestedEntityId);
  }

  public ResponseStatusException denyAsNotFound(
      UUID principalUserId, String requestedEntityType, UUID requestedEntityId) {
    // if/else, not a switch: a switch over another class's enum makes javac emit a synthetic
    // AuthorizationDenialAuditService$1, which ArchitectureTest's service naming rule rejects.
    DenialAuditBudget.Decision decision = budget.decide(principalUserId);
    if (decision == DenialAuditBudget.Decision.EXACT) {
      writeRepository.insert(
          principalUserId, requestedEntityType, requestedEntityId, REASON_NOT_FOUND);
    } else if (decision == DenialAuditBudget.Decision.SUMMARY) {
      writeRepository.insert(principalUserId, SUMMARY_ENTITY_TYPE, null, REASON_RATE_LIMITED);
    }
    // SUPPRESS: budget and summary used up in this window - no row, same response.
    return new ResponseStatusException(HttpStatus.NOT_FOUND, GENERIC_NOT_FOUND_DETAIL);
  }
}
