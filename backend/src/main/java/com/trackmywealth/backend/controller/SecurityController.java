package com.trackmywealth.backend.controller;

import com.trackmywealth.backend.dto.CreateSecurityRequest;
import com.trackmywealth.backend.dto.SecurityCreation;
import com.trackmywealth.backend.dto.SecurityResponse;
import com.trackmywealth.backend.security.AuthenticatedUserPrincipal;
import com.trackmywealth.backend.service.SecurityService;
import com.trackmywealth.backend.web.SuccessStatus;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.UUID;
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

/** US-12-01: the shared security master - lookup without persistence, and find-or-create. */
@RestController
@RequestMapping(SecurityController.BASE_PATH)
@Tag(name = "Securities")
public class SecurityController {

  // Shared with RateLimitFilter, which limits creation per source (a write to global data).
  public static final String BASE_PATH = "/api/v1/securities";
  public static final String IGNORED_FIELDS_HEADER = "X-Security-Ignored-Fields";

  private final SecurityService securityService;

  public SecurityController(SecurityService securityService) {
    this.securityService = securityService;
  }

  /** 200 with the existing record, 404 if none - never creates one (FR-SMD-007). */
  @Operation(operationId = "findSecurity", summary = "Find a security by ISIN")
  @GetMapping
  public SecurityResponse lookup(
      @RequestParam String isin, @AuthenticationPrincipal AuthenticatedUserPrincipal actor) {
    return securityService.lookup(isin, actor);
  }

  /** The only way to find a security without an ISIN again; 404 if unknown. */
  @Operation(operationId = "getSecurity", summary = "Read a security")
  @GetMapping("/{id}")
  public SecurityResponse get(
      @PathVariable UUID id, @AuthenticationPrincipal AuthenticatedUserPrincipal actor) {
    return securityService.get(id, actor);
  }

  /**
   * 201 when this call created the record, 200 when it already existed (returned unchanged). In the
   * latter case {@value #IGNORED_FIELDS_HEADER} names the supplied values that differ from the
   * stored ones and were ignored, so a client can tell its assumption was wrong.
   */
  @SuccessStatus({HttpStatus.CREATED, HttpStatus.OK})
  @Operation(operationId = "findOrCreateSecurity", summary = "Find a security or create it")
  @PostMapping
  public ResponseEntity<SecurityResponse> findOrCreate(
      @Valid @RequestBody CreateSecurityRequest request,
      @AuthenticationPrincipal AuthenticatedUserPrincipal actor) {
    SecurityCreation result = securityService.findOrCreate(request, actor);
    ResponseEntity.BodyBuilder response =
        ResponseEntity.status(result.created() ? HttpStatus.CREATED : HttpStatus.OK);
    if (!result.ignoredFields().isEmpty()) {
      response.header(IGNORED_FIELDS_HEADER, String.join(",", result.ignoredFields()));
    }
    return response.body(result.security());
  }
}
