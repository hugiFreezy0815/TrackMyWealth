package com.trackmywealth.backend.controller;

import com.trackmywealth.backend.dto.CreateFinancialInstitutionRequest;
import com.trackmywealth.backend.dto.FinancialInstitutionSummaryResponse;
import com.trackmywealth.backend.dto.InstitutionCatalogueEntrySummaryResponse;
import com.trackmywealth.backend.dto.InstitutionSummaryResponse;
import com.trackmywealth.backend.security.AuthenticatedUserPrincipal;
import com.trackmywealth.backend.service.InstitutionService;
import com.trackmywealth.backend.service.InstitutionSummaryService;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * US-04-01, US-04-03 (summary, gated per-account by {@link InstitutionSummaryService} - see its own
 * Javadoc for why that differs from every other institution-scoped endpoint). Reachable by any
 * authenticated caller (catalogue search needs no workspace at all - it's shared, non-tenant-scoped
 * reference data); creating an institution needs one, which {@link
 * InstitutionService#createInstitution} itself checks for.
 */
@RestController
@RequestMapping("/api/v1/institutions")
public class InstitutionController {

  private final InstitutionService institutionService;
  private final InstitutionSummaryService institutionSummaryService;

  public InstitutionController(
      InstitutionService institutionService, InstitutionSummaryService institutionSummaryService) {
    this.institutionService = institutionService;
    this.institutionSummaryService = institutionSummaryService;
  }

  @GetMapping("/catalogue")
  public Page<InstitutionCatalogueEntrySummaryResponse> searchCatalogue(
      @RequestParam(required = false) String query, Pageable pageable) {
    return institutionService.searchCatalogue(query, pageable);
  }

  @PostMapping
  public ResponseEntity<FinancialInstitutionSummaryResponse> createInstitution(
      @Valid @RequestBody CreateFinancialInstitutionRequest request,
      @AuthenticationPrincipal AuthenticatedUserPrincipal actor) {
    return ResponseEntity.status(HttpStatus.CREATED)
        .body(institutionService.createInstitution(request, actor.workspaceId()));
  }

  @GetMapping("/{institutionId}/summary")
  public InstitutionSummaryResponse getSummary(
      @PathVariable UUID institutionId, @AuthenticationPrincipal AuthenticatedUserPrincipal actor) {
    return institutionSummaryService.getSummary(institutionId, actor);
  }
}
