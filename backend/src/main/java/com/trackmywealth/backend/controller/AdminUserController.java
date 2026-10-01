package com.trackmywealth.backend.controller;

import com.trackmywealth.backend.dto.CreateUserRequest;
import com.trackmywealth.backend.dto.EditUserRequest;
import com.trackmywealth.backend.dto.UserSummaryResponse;
import com.trackmywealth.backend.security.AuthenticatedUserPrincipal;
import com.trackmywealth.backend.service.AdminUserService;
import com.trackmywealth.backend.web.IfMatchVersionParser;
import com.trackmywealth.backend.web.VersionedResponse;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * US-02-01. Reachable only by {@code SYSTEM_ADMINISTRATOR} - enforced in {@code SecurityConfig},
 * not here (FR-USR-009: role-based authorization, not per-endpoint checks scattered through
 * controllers).
 */
@RestController
@RequestMapping("/api/v1/admin/users")
public class AdminUserController {

  private final AdminUserService adminUserService;

  public AdminUserController(AdminUserService adminUserService) {
    this.adminUserService = adminUserService;
  }

  @PostMapping
  public ResponseEntity<UserSummaryResponse> createUser(
      @Valid @RequestBody CreateUserRequest request,
      @AuthenticationPrincipal AuthenticatedUserPrincipal actor) {
    UserSummaryResponse response =
        adminUserService.createUser(request, actor.userId(), actor.workspaceId());
    return VersionedResponse.created(response, response.version());
  }

  @GetMapping("/{id}")
  public ResponseEntity<UserSummaryResponse> getUser(@PathVariable UUID id) {
    UserSummaryResponse response = adminUserService.getUser(id);
    return VersionedResponse.ok(response, response.version());
  }

  @PatchMapping("/{id}")
  public ResponseEntity<UserSummaryResponse> editUser(
      @PathVariable UUID id,
      @RequestHeader(value = IfMatchVersionParser.HEADER, required = false) String ifMatch,
      @Valid @RequestBody EditUserRequest request,
      @AuthenticationPrincipal AuthenticatedUserPrincipal actor) {
    UserSummaryResponse response =
        adminUserService.editUser(id, request, IfMatchVersionParser.parse(ifMatch), actor.userId());
    return VersionedResponse.ok(response, response.version());
  }

  @PostMapping("/{id}/disable")
  public ResponseEntity<UserSummaryResponse> disableUser(
      @PathVariable UUID id,
      @RequestHeader(value = IfMatchVersionParser.HEADER, required = false) String ifMatch,
      @AuthenticationPrincipal AuthenticatedUserPrincipal actor) {
    UserSummaryResponse response =
        adminUserService.disableUser(id, IfMatchVersionParser.parse(ifMatch), actor.userId());
    return VersionedResponse.ok(response, response.version());
  }

  @PostMapping("/{id}/reactivate")
  public ResponseEntity<UserSummaryResponse> reactivateUser(
      @PathVariable UUID id,
      @RequestHeader(value = IfMatchVersionParser.HEADER, required = false) String ifMatch,
      @AuthenticationPrincipal AuthenticatedUserPrincipal actor) {
    UserSummaryResponse response =
        adminUserService.reactivateUser(id, IfMatchVersionParser.parse(ifMatch), actor.userId());
    return VersionedResponse.ok(response, response.version());
  }
}
