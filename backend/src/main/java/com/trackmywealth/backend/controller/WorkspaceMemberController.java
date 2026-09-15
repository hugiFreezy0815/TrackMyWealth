package com.trackmywealth.backend.controller;

import com.trackmywealth.backend.dto.WorkspaceMemberSummaryResponse;
import com.trackmywealth.backend.security.AuthenticatedUserPrincipal;
import com.trackmywealth.backend.service.WorkspaceMemberService;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
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

  @PostMapping("/{memberId}/deactivate")
  public WorkspaceMemberSummaryResponse deactivateMember(
      @PathVariable UUID memberId, @AuthenticationPrincipal AuthenticatedUserPrincipal actor) {
    return workspaceMemberService.deactivateMember(memberId, actor);
  }
}
