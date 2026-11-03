package com.trackmywealth.backend.service;

import com.trackmywealth.backend.entity.Portfolio;
import com.trackmywealth.backend.repository.PortfolioRepository;
import com.trackmywealth.backend.security.AuthenticatedUserPrincipal;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Shared portfolio lookup guard, mirroring {@link AccountLookupService}. Endpoint-facing callers
 * use the actor-aware overload so missing/cross-tenant ids produce the uniform audited 404 required
 * by US-28-02.
 */
@Service
public class PortfolioLookupService {

  // authorization_denial_log.requested_entity_type. Denials logged before V111 (US-04-06) name the
  // same entity "FinancialInstitution"; the log is evidence and is left as it was written.
  private static final String PORTFOLIO_ENTITY_TYPE = "Portfolio";

  private final PortfolioRepository portfolioRepository;
  private final AuthorizationDenialAuditService authorizationDenialAuditService;

  public PortfolioLookupService(
      PortfolioRepository portfolioRepository,
      AuthorizationDenialAuditService authorizationDenialAuditService) {
    this.portfolioRepository = portfolioRepository;
    this.authorizationDenialAuditService = authorizationDenialAuditService;
  }

  public Portfolio findPortfolioOrThrow(UUID portfolioId, AuthenticatedUserPrincipal actor) {
    return portfolioRepository
        .findById(portfolioId)
        .orElseThrow(
            () ->
                authorizationDenialAuditService.denyAsNotFound(
                    actor, PORTFOLIO_ENTITY_TYPE, portfolioId));
  }
}
