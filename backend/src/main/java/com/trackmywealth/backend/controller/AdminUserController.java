package com.trackmywealth.backend.controller;

import com.trackmywealth.backend.dto.CreateUserRequest;
import com.trackmywealth.backend.dto.EditUserRequest;
import com.trackmywealth.backend.dto.UserSummaryResponse;
import com.trackmywealth.backend.security.AuthenticatedUserPrincipal;
import com.trackmywealth.backend.service.AdminUserService;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
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
    return ResponseEntity.status(HttpStatus.CREATED)
        .body(adminUserService.createUser(request, actor.userId()));
  }

  @PatchMapping("/{id}")
  public UserSummaryResponse editUser(
      @PathVariable UUID id,
      @Valid @RequestBody EditUserRequest request,
      @AuthenticationPrincipal AuthenticatedUserPrincipal actor) {
    return adminUserService.editUser(id, request, actor.userId());
  }

  @PostMapping("/{id}/disable")
  public UserSummaryResponse disableUser(
      @PathVariable UUID id, @AuthenticationPrincipal AuthenticatedUserPrincipal actor) {
    return adminUserService.disableUser(id, actor.userId());
  }

  @PostMapping("/{id}/reactivate")
  public UserSummaryResponse reactivateUser(
      @PathVariable UUID id, @AuthenticationPrincipal AuthenticatedUserPrincipal actor) {
    return adminUserService.reactivateUser(id, actor.userId());
  }
}
