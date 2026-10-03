package com.trackmywealth.backend.controller;

import com.trackmywealth.backend.dto.UpdateWorkspaceCurrencyRequest;
import com.trackmywealth.backend.dto.WorkspaceResponse;
import com.trackmywealth.backend.security.AuthenticatedUserPrincipal;
import com.trackmywealth.backend.service.WorkspaceService;
import com.trackmywealth.backend.web.IfMatchVersionParser;
import com.trackmywealth.backend.web.VersionedResponse;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** US-06-05: current workspace settings and its default display currency. */
@RestController
@RequestMapping("/api/v1/workspace")
public class WorkspaceController {

  private final WorkspaceService workspaceService;

  public WorkspaceController(WorkspaceService workspaceService) {
    this.workspaceService = workspaceService;
  }

  @GetMapping
  public ResponseEntity<WorkspaceResponse> getCurrent(
      @AuthenticationPrincipal AuthenticatedUserPrincipal actor) {
    WorkspaceResponse response = workspaceService.getCurrent(actor);
    return VersionedResponse.ok(response, response.version());
  }

  @PutMapping("/currency")
  public ResponseEntity<WorkspaceResponse> updateCurrency(
      @Valid @RequestBody UpdateWorkspaceCurrencyRequest request,
      @RequestHeader(value = IfMatchVersionParser.HEADER, required = false) String ifMatch,
      @AuthenticationPrincipal AuthenticatedUserPrincipal actor) {
    WorkspaceResponse response =
        workspaceService.updateCurrency(request, IfMatchVersionParser.parse(ifMatch), actor);
    return VersionedResponse.ok(response, response.version());
  }
}
