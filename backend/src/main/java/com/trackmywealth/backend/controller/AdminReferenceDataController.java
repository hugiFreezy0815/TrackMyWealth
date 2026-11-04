package com.trackmywealth.backend.controller;

import com.trackmywealth.backend.dto.ReferenceDataResponse;
import com.trackmywealth.backend.service.ReferenceDataService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * US-01-04: the loaded reference-data package, for administrators. Reachable only by {@code
 * SYSTEM_ADMINISTRATOR} - enforced in {@code SecurityConfig} for every {@code /api/v1/admin/**}
 * path, not here (FR-USR-009).
 */
@RestController
@RequestMapping("/api/v1/admin/reference-data")
@Tag(name = "Admin")
public class AdminReferenceDataController {

  private final ReferenceDataService referenceDataService;

  public AdminReferenceDataController(ReferenceDataService referenceDataService) {
    this.referenceDataService = referenceDataService;
  }

  @Operation(operationId = "getReferenceData", summary = "Read the loaded reference-data package")
  @GetMapping
  public ReferenceDataResponse current() {
    return referenceDataService.current();
  }
}
