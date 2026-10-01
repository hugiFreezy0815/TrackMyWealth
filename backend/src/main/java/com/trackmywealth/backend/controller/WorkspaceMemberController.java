package com.trackmywealth.backend.controller;

import com.trackmywealth.backend.dto.WorkspaceMemberSummaryResponse;
import com.trackmywealth.backend.security.AuthenticatedUserPrincipal;
import com.trackmywealth.backend.service.WorkspaceMemberService;
import com.trackmywealth.backend.web.IfMatchVersionParser;
import com.trackmywealth.backend.web.VersionedResponse;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** US-03-04 (self-service only - see {@code WorkspaceMemberService}'s own Javadoc). */
@RestController
@RequestMapping("/api/v1/workspace-members")
public class WorkspaceMemberController {

  private final WorkspaceMemberService workspaceMemberService;

  public WorkspaceMemberController(WorkspaceMemberService workspaceMemberService) {
    this.workspaceMemberService = workspaceMemberService;
  }

  @GetMapping("/{memberId}")
  public ResponseEntity<WorkspaceMemberSummaryResponse> getMember(
      @PathVariable UUID memberId, @AuthenticationPrincipal AuthenticatedUserPrincipal actor) {
    WorkspaceMemberSummaryResponse response = workspaceMemberService.getMember(memberId, actor);
    return VersionedResponse.ok(response, response.version());
  }

  @PostMapping("/{memberId}/deactivate")
  public ResponseEntity<WorkspaceMemberSummaryResponse> deactivateMember(
      @PathVariable UUID memberId,
      @RequestHeader(value = IfMatchVersionParser.HEADER, required = false) String ifMatch,
      @AuthenticationPrincipal AuthenticatedUserPrincipal actor) {
    WorkspaceMemberSummaryResponse response =
        workspaceMemberService.deactivateMember(
            memberId, IfMatchVersionParser.parse(ifMatch), actor);
    return VersionedResponse.ok(response, response.version());
  }
}
