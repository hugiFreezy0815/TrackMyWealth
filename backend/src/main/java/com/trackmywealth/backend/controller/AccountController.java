package com.trackmywealth.backend.controller;

import com.trackmywealth.backend.dto.AccountSummaryResponse;
import com.trackmywealth.backend.dto.CreateAccountRequest;
import com.trackmywealth.backend.dto.UpdateAccountRequest;
import com.trackmywealth.backend.security.AuthenticatedUserPrincipal;
import com.trackmywealth.backend.service.AccountService;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** US-05-01, US-05-02, US-05-03, US-03-03 (read/edit gated by {@code AccessControlService}). */
@RestController
@RequestMapping("/api/v1/accounts")
public class AccountController {

  private final AccountService accountService;

  public AccountController(AccountService accountService) {
    this.accountService = accountService;
  }

  @PostMapping
  public ResponseEntity<AccountSummaryResponse> createAccount(
      @Valid @RequestBody CreateAccountRequest request,
      @AuthenticationPrincipal AuthenticatedUserPrincipal actor) {
    return ResponseEntity.status(HttpStatus.CREATED)
        .body(accountService.createAccount(request, actor.workspaceId()));
  }

  @GetMapping("/{accountId}")
  public AccountSummaryResponse getAccount(
      @PathVariable UUID accountId, @AuthenticationPrincipal AuthenticatedUserPrincipal actor) {
    return accountService.getAccount(accountId, actor);
  }

  @PutMapping("/{accountId}")
  public AccountSummaryResponse updateAccount(
      @PathVariable UUID accountId,
      @Valid @RequestBody UpdateAccountRequest request,
      @AuthenticationPrincipal AuthenticatedUserPrincipal actor) {
    return accountService.updateAccount(accountId, request, actor);
  }

  @PostMapping("/{accountId}/archive")
  public AccountSummaryResponse archiveAccount(
      @PathVariable UUID accountId, @AuthenticationPrincipal AuthenticatedUserPrincipal actor) {
    return accountService.archiveAccount(accountId, actor);
  }

  @PostMapping("/{accountId}/restore")
  public AccountSummaryResponse restoreAccount(
      @PathVariable UUID accountId, @AuthenticationPrincipal AuthenticatedUserPrincipal actor) {
    return accountService.restoreAccount(accountId, actor);
  }
}
