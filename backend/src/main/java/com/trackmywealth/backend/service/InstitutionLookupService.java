package com.trackmywealth.backend.service;

import com.trackmywealth.backend.entity.FinancialInstitution;
import com.trackmywealth.backend.repository.FinancialInstitutionRepository;
import com.trackmywealth.backend.security.AuthenticatedUserPrincipal;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Shared institution lookup guard, mirroring {@link AccountLookupService}. Endpoint-facing callers
 * use the actor-aware overload so missing/cross-tenant ids produce the uniform audited 404 required
 * by US-28-02.
 */
@Service
public class InstitutionLookupService {

  private static final String INSTITUTION_ENTITY_TYPE = "FinancialInstitution";

  private final FinancialInstitutionRepository financialInstitutionRepository;
  private final AuthorizationDenialAuditService authorizationDenialAuditService;

  public InstitutionLookupService(
      FinancialInstitutionRepository financialInstitutionRepository,
      AuthorizationDenialAuditService authorizationDenialAuditService) {
    this.financialInstitutionRepository = financialInstitutionRepository;
    this.authorizationDenialAuditService = authorizationDenialAuditService;
  }

  public FinancialInstitution findInstitutionOrThrow(
      UUID institutionId, AuthenticatedUserPrincipal actor) {
    return financialInstitutionRepository
        .findById(institutionId)
        .orElseThrow(
            () ->
                authorizationDenialAuditService.denyAsNotFound(
                    actor, INSTITUTION_ENTITY_TYPE, institutionId));
  }
}
