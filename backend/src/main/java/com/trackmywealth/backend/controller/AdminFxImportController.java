package com.trackmywealth.backend.controller;

import com.trackmywealth.backend.dto.FxImportSettingsResponse;
import com.trackmywealth.backend.dto.UpdateFxImportIntervalRequest;
import com.trackmywealth.backend.error.ProblemCode;
import com.trackmywealth.backend.security.AuthenticatedUserPrincipal;
import com.trackmywealth.backend.service.AdminFxImportService;
import com.trackmywealth.backend.web.IfMatchVersionParser;
import com.trackmywealth.backend.web.ProblemCodes;
import com.trackmywealth.backend.web.ResponseHeader;
import com.trackmywealth.backend.web.SendsHeaders;
import com.trackmywealth.backend.web.VersionedResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * US-06-07 (#227): the FX import interval, changed at runtime. Reachable only by {@code
 * SYSTEM_ADMINISTRATOR} - enforced in {@code SecurityConfig} for every {@code /api/v1/admin/**}
 * path, not here (FR-USR-009). Administration rights only: a global setting, no workspace data
 * (FR-USR-010).
 */
@RestController
@RequestMapping("/api/v1/admin/fx-import")
@Tag(name = "Admin")
public class AdminFxImportController {

  private final AdminFxImportService adminFxImportService;

  public AdminFxImportController(AdminFxImportService adminFxImportService) {
    this.adminFxImportService = adminFxImportService;
  }

  @Operation(operationId = "getFxImportSettings", summary = "Read the FX rate import settings")
  @GetMapping
  @SendsHeaders(ResponseHeader.ETAG)
  public ResponseEntity<FxImportSettingsResponse> current() {
    FxImportSettingsResponse response = adminFxImportService.current();
    return VersionedResponse.ok(response, response.version());
  }

  @Operation(
      operationId = "updateFxImportInterval",
      summary = "Change how often FX rates are imported")
  @ProblemCodes({ProblemCode.CONFLICT, ProblemCode.UNPROCESSABLE})
  @PutMapping
  @SendsHeaders(ResponseHeader.ETAG)
  public ResponseEntity<FxImportSettingsResponse> updateInterval(
      @RequestHeader(value = IfMatchVersionParser.HEADER, required = false) String ifMatch,
      @Valid @RequestBody UpdateFxImportIntervalRequest request,
      @AuthenticationPrincipal AuthenticatedUserPrincipal actor) {
    FxImportSettingsResponse response =
        adminFxImportService.updateInterval(
            request, IfMatchVersionParser.parse(ifMatch), actor.userId());
    return VersionedResponse.ok(response, response.version());
  }
}
