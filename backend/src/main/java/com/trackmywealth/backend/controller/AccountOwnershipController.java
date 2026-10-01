package com.trackmywealth.backend.controller;

import com.trackmywealth.backend.dto.AccountOwnershipSetResponse;
import com.trackmywealth.backend.dto.AssignAccountOwnershipRequest;
import com.trackmywealth.backend.security.AuthenticatedUserPrincipal;
import com.trackmywealth.backend.service.AccountOwnershipService;
import com.trackmywealth.backend.web.IfMatchVersionParser;
import com.trackmywealth.backend.web.VersionedResponse;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
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
  public ResponseEntity<AccountOwnershipSetResponse> assignOwnership(
      @PathVariable UUID accountId,
      @RequestHeader(value = IfMatchVersionParser.HEADER, required = false) String ifMatch,
      @Valid @RequestBody AssignAccountOwnershipRequest request,
      @AuthenticationPrincipal AuthenticatedUserPrincipal actor) {
    AccountOwnershipSetResponse response =
        accountOwnershipService.assignOwnership(
            accountId, request, IfMatchVersionParser.parse(ifMatch), actor);
    return VersionedResponse.ok(response, response.version());
  }

  @GetMapping
  public ResponseEntity<AccountOwnershipSetResponse> currentOwnership(
      @PathVariable UUID accountId, @AuthenticationPrincipal AuthenticatedUserPrincipal actor) {
    AccountOwnershipSetResponse response =
        accountOwnershipService.currentOwnership(accountId, actor);
    return VersionedResponse.ok(response, response.version());
  }
}
