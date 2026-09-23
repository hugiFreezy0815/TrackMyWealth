package com.trackmywealth.backend.controller;

import com.trackmywealth.backend.dto.CreateSecurityRequest;
import com.trackmywealth.backend.dto.SecurityCreation;
import com.trackmywealth.backend.dto.SecurityResponse;
import com.trackmywealth.backend.security.AuthenticatedUserPrincipal;
import com.trackmywealth.backend.service.SecurityService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** US-12-01: the shared security master - lookup without persistence, and find-or-create. */
@RestController
@RequestMapping("/api/v1/securities")
public class SecurityController {

  private final SecurityService securityService;

  public SecurityController(SecurityService securityService) {
    this.securityService = securityService;
  }

  /** 200 with the existing record, 404 if none - never creates one (FR-SMD-007). */
  @GetMapping
  public SecurityResponse lookup(
      @RequestParam String isin, @AuthenticationPrincipal AuthenticatedUserPrincipal actor) {
    return securityService.lookup(isin, actor);
  }

  /** 201 when this call created the record, 200 when it already existed (returned unchanged). */
  @PostMapping
  public ResponseEntity<SecurityResponse> findOrCreate(
      @Valid @RequestBody CreateSecurityRequest request,
      @AuthenticationPrincipal AuthenticatedUserPrincipal actor) {
    SecurityCreation result = securityService.findOrCreate(request, actor);
    return ResponseEntity.status(result.created() ? HttpStatus.CREATED : HttpStatus.OK)
        .body(result.security());
  }
}
