package com.trackmywealth.backend.controller;

import com.trackmywealth.backend.dto.CreateFinancialInstitutionRequest;
import com.trackmywealth.backend.dto.FinancialInstitutionSummaryResponse;
import com.trackmywealth.backend.dto.InstitutionCatalogueEntrySummaryResponse;
import com.trackmywealth.backend.security.AuthenticatedUserPrincipal;
import com.trackmywealth.backend.service.InstitutionService;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** US-04-01. Reachable by any authenticated caller with an active workspace membership. */
@RestController
@RequestMapping("/api/v1/institutions")
public class InstitutionController {

  private final InstitutionService institutionService;

  public InstitutionController(InstitutionService institutionService) {
    this.institutionService = institutionService;
  }

  @GetMapping("/catalogue")
  public Page<InstitutionCatalogueEntrySummaryResponse> searchCatalogue(
      @RequestParam(required = false) String query,
      Pageable pageable,
      @AuthenticationPrincipal AuthenticatedUserPrincipal actor) {
    return institutionService.searchCatalogue(actor.userId(), query, pageable);
  }

  @PostMapping
  public ResponseEntity<FinancialInstitutionSummaryResponse> createInstitution(
      @Valid @RequestBody CreateFinancialInstitutionRequest request,
      @AuthenticationPrincipal AuthenticatedUserPrincipal actor) {
    return ResponseEntity.status(HttpStatus.CREATED)
        .body(institutionService.createInstitution(request, actor.userId(), actor.workspaceId()));
  }
}
