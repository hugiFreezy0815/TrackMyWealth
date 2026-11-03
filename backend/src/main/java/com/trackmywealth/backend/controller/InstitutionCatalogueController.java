package com.trackmywealth.backend.controller;

import com.trackmywealth.backend.dto.InstitutionCatalogueEntrySummaryResponse;
import com.trackmywealth.backend.dto.PageResponse;
import com.trackmywealth.backend.service.InstitutionCatalogueService;
import com.trackmywealth.backend.web.Paged;
import com.trackmywealth.backend.web.Paged.SortField;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.data.domain.Pageable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * US-04-01: search the shared institution catalogue - the real banks and brokers a portfolio may be
 * linked to. Shared, non-tenant-scoped reference data, so any authenticated caller may search it,
 * with or without a workspace. The only {@code /institutions} endpoint left after US-04-06.
 */
@RestController
@RequestMapping("/api/v1/institutions")
@Tag(name = "Institutions")
public class InstitutionCatalogueController {

  private final InstitutionCatalogueService institutionCatalogueService;

  public InstitutionCatalogueController(InstitutionCatalogueService institutionCatalogueService) {
    this.institutionCatalogueService = institutionCatalogueService;
  }

  @Operation(
      operationId = "searchInstitutionCatalogue",
      summary = "Search the institution catalogue")
  @GetMapping("/catalogue")
  public PageResponse<InstitutionCatalogueEntrySummaryResponse> searchCatalogue(
      @RequestParam(required = false) String query,
      @Paged(sort = @SortField(name = "name"), defaultSort = "name,asc") Pageable pageable) {
    return PageResponse.of(institutionCatalogueService.searchCatalogue(query, pageable));
  }
}
