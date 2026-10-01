package com.trackmywealth.backend.service;

import com.trackmywealth.backend.entity.Account;
import com.trackmywealth.backend.repository.AccountRepository;
import com.trackmywealth.backend.security.AuthenticatedUserPrincipal;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Shared account lookup guard. Endpoint-facing callers use {@link #findAccountOrThrow(UUID,
 * AuthenticatedUserPrincipal)} so a missing/cross-tenant id is both non-enumerating and recorded in
 * {@code authorization_denial_log} (US-28-02). The actor-less overload is reserved for internal
 * calculations where the id did not come from an API caller.
 */
@Service
public class AccountLookupService {

  private static final String ACCOUNT_ENTITY_TYPE = "Account";

  private final AccountRepository accountRepository;
  private final AuthorizationDenialAuditService authorizationDenialAuditService;

  public AccountLookupService(
      AccountRepository accountRepository,
      AuthorizationDenialAuditService authorizationDenialAuditService) {
    this.accountRepository = accountRepository;
    this.authorizationDenialAuditService = authorizationDenialAuditService;
  }

  public Account findAccountOrThrow(UUID accountId, AuthenticatedUserPrincipal actor) {
    return accountRepository
        .findById(accountId)
        .orElseThrow(
            () ->
                authorizationDenialAuditService.denyAsNotFound(
                    actor, ACCOUNT_ENTITY_TYPE, accountId));
  }

  /**
   * Internal-only lookup for ids already derived from authorized persisted state rather than
   * supplied by a client. New endpoint paths must use the actor-aware overload above.
   */
  public Account findAccountOrThrow(UUID accountId) {
    return accountRepository
        .findById(accountId)
        .orElseThrow(
            () ->
                new IllegalStateException(
                    "Expected authorized account " + accountId + " to exist."));
  }
}
