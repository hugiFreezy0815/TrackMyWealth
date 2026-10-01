package com.trackmywealth.backend.controller;

import com.trackmywealth.backend.dto.AccountSummaryResponse;
import com.trackmywealth.backend.dto.CreateAccountRequest;
import com.trackmywealth.backend.dto.ReassignAccountInstitutionRequest;
import com.trackmywealth.backend.dto.UpdateAccountRequest;
import com.trackmywealth.backend.security.AuthenticatedUserPrincipal;
import com.trackmywealth.backend.service.AccountService;
import com.trackmywealth.backend.web.IfMatchVersionParser;
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
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * US-05-01, US-05-02, US-05-03, US-04-04, US-03-03 (read/edit gated by {@code
 * AccessControlService}).
 */
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
    AccountSummaryResponse created = accountService.createAccount(request, actor);
    return withEtag(ResponseEntity.status(HttpStatus.CREATED), created);
  }

  @GetMapping("/{accountId}")
  public ResponseEntity<AccountSummaryResponse> getAccount(
      @PathVariable UUID accountId, @AuthenticationPrincipal AuthenticatedUserPrincipal actor) {
    AccountSummaryResponse account = accountService.getAccount(accountId, actor);
    return withEtag(ResponseEntity.ok(), account);
  }

  @PutMapping("/{accountId}")
  public ResponseEntity<AccountSummaryResponse> updateAccount(
      @PathVariable UUID accountId,
      @Valid @RequestBody UpdateAccountRequest request,
      @RequestHeader(value = IfMatchVersionParser.HEADER, required = false) String ifMatch,
      @AuthenticationPrincipal AuthenticatedUserPrincipal actor) {
    AccountSummaryResponse updated =
        accountService.updateAccount(
            accountId, request, IfMatchVersionParser.parse(ifMatch), actor);
    return withEtag(ResponseEntity.ok(), updated);
  }

  @PostMapping("/{accountId}/archive")
  public ResponseEntity<AccountSummaryResponse> archiveAccount(
      @PathVariable UUID accountId,
      @RequestHeader(value = IfMatchVersionParser.HEADER, required = false) String ifMatch,
      @AuthenticationPrincipal AuthenticatedUserPrincipal actor) {
    AccountSummaryResponse archived =
        accountService.archiveAccount(
            accountId, IfMatchVersionParser.parse(ifMatch), actor);
    return withEtag(ResponseEntity.ok(), archived);
  }

  @PostMapping("/{accountId}/restore")
  public ResponseEntity<AccountSummaryResponse> restoreAccount(
      @PathVariable UUID accountId,
      @RequestHeader(value = IfMatchVersionParser.HEADER, required = false) String ifMatch,
      @AuthenticationPrincipal AuthenticatedUserPrincipal actor) {
    AccountSummaryResponse restored =
        accountService.restoreAccount(
            accountId, IfMatchVersionParser.parse(ifMatch), actor);
    return withEtag(ResponseEntity.ok(), restored);
  }

  @PostMapping("/{accountId}/reassign-institution")
  public ResponseEntity<AccountSummaryResponse> reassignInstitution(
      @PathVariable UUID accountId,
      @Valid @RequestBody ReassignAccountInstitutionRequest request,
      @RequestHeader(value = IfMatchVersionParser.HEADER, required = false) String ifMatch,
      @AuthenticationPrincipal AuthenticatedUserPrincipal actor) {
    AccountSummaryResponse reassigned =
        accountService.reassignInstitution(
            accountId, request, IfMatchVersionParser.parse(ifMatch), actor);
    return withEtag(ResponseEntity.ok(), reassigned);
  }

  private static ResponseEntity<AccountSummaryResponse> withEtag(
      ResponseEntity.BodyBuilder builder, AccountSummaryResponse body) {
    return builder.eTag(IfMatchVersionParser.toEtag(body.version())).body(body);
  }
}
