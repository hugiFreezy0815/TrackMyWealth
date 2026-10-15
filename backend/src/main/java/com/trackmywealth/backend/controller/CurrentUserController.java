package com.trackmywealth.backend.controller;

import com.trackmywealth.backend.dto.CurrentUserResponse;
import com.trackmywealth.backend.security.AuthenticatedUserPrincipal;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * US-02-06: the caller's own verified identity and role, so the mobile app never decides what to
 * show from a token it decoded itself. Deliberately outside {@code /api/v1/auth/**}, which is
 * public (SecurityConfig): here a missing or expired token is a plain 401, the signal the client
 * refreshes on. Admin endpoints still enforce the role themselves; this only drives the UI.
 */
@RestController
public class CurrentUserController {

  @GetMapping("/api/v1/users/me")
  public ResponseEntity<CurrentUserResponse> currentUser(
      @AuthenticationPrincipal AuthenticatedUserPrincipal principal) {
    return ResponseEntity.ok(
        new CurrentUserResponse(principal.userId(), principal.role(), principal.language()));
  }
}
