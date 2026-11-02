package com.trackmywealth.backend.service;

import com.trackmywealth.backend.entity.Portfolio;
import com.trackmywealth.backend.repository.PortfolioRepository;
import com.trackmywealth.backend.security.AuthenticatedUserPrincipal;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Shared institution lookup guard, mirroring {@link AccountLookupService}. Endpoint-facing callers
 * use the actor-aware overload so missing/cross-tenant ids produce the uniform audited 404 required
 * by US-28-02.
 */
@Service
public class PortfolioLookupService {

  private static final String INSTITUTION_ENTITY_TYPE = "Portfolio";

  private final PortfolioRepository portfolioRepository;
  private final AuthorizationDenialAuditService authorizationDenialAuditService;

  public PortfolioLookupService(
      PortfolioRepository portfolioRepository,
      AuthorizationDenialAuditService authorizationDenialAuditService) {
    this.portfolioRepository = portfolioRepository;
    this.authorizationDenialAuditService = authorizationDenialAuditService;
  }

  public Portfolio findPortfolioOrThrow(UUID institutionId, AuthenticatedUserPrincipal actor) {
    return portfolioRepository
        .findById(institutionId)
        .orElseThrow(
            () ->
                authorizationDenialAuditService.denyAsNotFound(
                    actor, INSTITUTION_ENTITY_TYPE, institutionId));
  }
}
