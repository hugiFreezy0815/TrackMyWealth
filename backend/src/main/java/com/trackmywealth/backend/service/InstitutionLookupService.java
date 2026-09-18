package com.trackmywealth.backend.service;

import com.trackmywealth.backend.entity.FinancialInstitution;
import com.trackmywealth.backend.repository.FinancialInstitutionRepository;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * Shared "look up this institution or 404" guard, mirroring {@link AccountLookupService}. {@code
 * SharingGrantService}'s own Javadoc previously explained why this wasn't extracted alongside
 * {@code findAccountOrThrow}: it had only one genuinely standalone caller, with {@code
 * AccountService}'s other lookup embedded inside {@code resolveInstitution}'s larger two-branch
 * method. US-04-04's {@code reassignInstitution} added a second standalone caller, which is what
 * tips the balance - RLS already confines the underlying {@code SELECT} to the caller's own
 * workspace, so a cross-workspace id simply isn't found, degrading safely to 404 rather than
 * needing a separate equality check.
 */
@Service
public class InstitutionLookupService {

  private final FinancialInstitutionRepository financialInstitutionRepository;

  public InstitutionLookupService(FinancialInstitutionRepository financialInstitutionRepository) {
    this.financialInstitutionRepository = financialInstitutionRepository;
  }

  public FinancialInstitution findInstitutionOrThrow(UUID institutionId) {
    return financialInstitutionRepository
        .findById(institutionId)
        .orElseThrow(
            () ->
                new ResponseStatusException(
                    HttpStatus.NOT_FOUND, "Financial institution not found."));
  }
}
