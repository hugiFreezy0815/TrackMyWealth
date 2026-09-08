package com.trackmywealth.backend.controller;

import com.trackmywealth.backend.dto.SessionSummaryResponse;
import com.trackmywealth.backend.security.AuthenticatedUserPrincipal;
import com.trackmywealth.backend.service.SessionService;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/** US-02-03: list and revoke the caller's own sessions. See {@link SessionService}. */
@RestController
public class SessionController {

  private final SessionService sessionService;

  public SessionController(SessionService sessionService) {
    this.sessionService = sessionService;
  }

  @GetMapping("/api/v1/sessions")
  public ResponseEntity<List<SessionSummaryResponse>> listSessions(
      @AuthenticationPrincipal AuthenticatedUserPrincipal principal) {
    return ResponseEntity.ok(
        sessionService.listSessions(principal.userId(), principal.sessionId()));
  }

  @PostMapping("/api/v1/sessions/{id}/revoke")
  public ResponseEntity<SessionSummaryResponse> revokeSession(
      @PathVariable UUID id, @AuthenticationPrincipal AuthenticatedUserPrincipal principal) {
    return ResponseEntity.ok(
        sessionService.revokeSession(id, principal.userId(), principal.sessionId()));
  }
}
