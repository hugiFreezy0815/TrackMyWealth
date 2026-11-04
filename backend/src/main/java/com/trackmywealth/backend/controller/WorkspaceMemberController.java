package com.trackmywealth.backend.controller;

import com.trackmywealth.backend.dto.WorkspaceMemberListItemResponse;
import com.trackmywealth.backend.dto.WorkspaceMemberSummaryResponse;
import com.trackmywealth.backend.error.ProblemCode;
import com.trackmywealth.backend.security.AuthenticatedUserPrincipal;
import com.trackmywealth.backend.service.WorkspaceMemberService;
import com.trackmywealth.backend.web.IfMatchVersionParser;
import com.trackmywealth.backend.web.ProblemCodes;
import com.trackmywealth.backend.web.VersionedResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** US-03-04 self-service membership and US-03-05 workspace member listing. */
@RestController
@RequestMapping("/api/v1/workspace-members")
@Tag(name = "Workspace")
public class WorkspaceMemberController {

  private final WorkspaceMemberService workspaceMemberService;

  public WorkspaceMemberController(WorkspaceMemberService workspaceMemberService) {
    this.workspaceMemberService = workspaceMemberService;
  }

  @Operation(operationId = "listWorkspaceMembers", summary = "List the workspace's members")
  @ProblemCodes({ProblemCode.NOT_FOUND, ProblemCode.UNPROCESSABLE})
  @GetMapping
  public List<WorkspaceMemberListItemResponse> listWorkspaceMembers(
      @AuthenticationPrincipal AuthenticatedUserPrincipal actor) {
    return workspaceMemberService.listWorkspaceMembers(actor);
  }

  @Operation(operationId = "getWorkspaceMember", summary = "Read a workspace member")
  @GetMapping("/{memberId}")
  public ResponseEntity<WorkspaceMemberSummaryResponse> getMember(
      @PathVariable UUID memberId, @AuthenticationPrincipal AuthenticatedUserPrincipal actor) {
    WorkspaceMemberSummaryResponse response = workspaceMemberService.getMember(memberId, actor);
    return VersionedResponse.ok(response, response.version());
  }

  @Operation(operationId = "deactivateWorkspaceMember", summary = "Deactivate a workspace member")
  @ProblemCodes({ProblemCode.CONFLICT})
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
