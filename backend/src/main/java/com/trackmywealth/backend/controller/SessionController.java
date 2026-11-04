package com.trackmywealth.backend.controller;

import com.trackmywealth.backend.dto.SessionSummaryResponse;
import com.trackmywealth.backend.security.AuthenticatedUserPrincipal;
import com.trackmywealth.backend.service.SessionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
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
@Tag(name = "Current user")
public class SessionController {

  // Referenced by RateLimitFilter (FR-AUT-010) so the two can never silently drift apart - the
  // mapping annotation below is the single source of truth for both. AntPathMatcher (used by the
  // filter) supports the same {id} placeholder syntax as Spring MVC's own mapping, so this one
  // literal works unmodified in both places - no hand-translated wildcard to keep in sync.
  public static final String REVOKE_PATH = "/api/v1/sessions/{id}/revoke";

  private final SessionService sessionService;

  public SessionController(SessionService sessionService) {
    this.sessionService = sessionService;
  }

  @Operation(operationId = "listSessions", summary = "List the signed-in user's sessions")
  @GetMapping("/api/v1/sessions")
  public ResponseEntity<List<SessionSummaryResponse>> listSessions(
      @AuthenticationPrincipal AuthenticatedUserPrincipal principal) {
    return ResponseEntity.ok(
        sessionService.listSessions(principal.userId(), principal.sessionId()));
  }

  @Operation(operationId = "revokeSession", summary = "Sign out one session")
  @PostMapping(REVOKE_PATH)
  public ResponseEntity<SessionSummaryResponse> revokeSession(
      @PathVariable UUID id, @AuthenticationPrincipal AuthenticatedUserPrincipal principal) {
    return ResponseEntity.ok(
        sessionService.revokeSession(id, principal.userId(), principal.sessionId()));
  }
}
