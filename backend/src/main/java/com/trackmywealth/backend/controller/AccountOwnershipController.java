package com.trackmywealth.backend.controller;

import com.trackmywealth.backend.dto.AccountOwnershipResponse;
import com.trackmywealth.backend.dto.AssignAccountOwnershipRequest;
import com.trackmywealth.backend.security.AuthenticatedUserPrincipal;
import com.trackmywealth.backend.service.AccountOwnershipService;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** US-03-02, US-03-03 (read/edit gated by {@code AccessControlService}). */
@RestController
@RequestMapping("/api/v1/accounts/{accountId}/ownership")
public class AccountOwnershipController {

  private final AccountOwnershipService accountOwnershipService;

  public AccountOwnershipController(AccountOwnershipService accountOwnershipService) {
    this.accountOwnershipService = accountOwnershipService;
  }

  @PutMapping
  public List<AccountOwnershipResponse> assignOwnership(
      @PathVariable UUID accountId,
      @Valid @RequestBody AssignAccountOwnershipRequest request,
      @AuthenticationPrincipal AuthenticatedUserPrincipal actor) {
    return accountOwnershipService.assignOwnership(accountId, request, actor);
  }

  @GetMapping
  public List<AccountOwnershipResponse> currentOwnership(
      @PathVariable UUID accountId, @AuthenticationPrincipal AuthenticatedUserPrincipal actor) {
    return accountOwnershipService.currentOwnership(accountId, actor);
  }
}
