package com.trackmywealth.backend.controller;

import com.trackmywealth.backend.dto.OpeningBalanceRequest;
import com.trackmywealth.backend.dto.OpeningBalanceResponse;
import com.trackmywealth.backend.security.AuthenticatedUserPrincipal;
import com.trackmywealth.backend.service.OpeningBalanceService;
import com.trackmywealth.backend.web.IfMatchVersionParser;
import com.trackmywealth.backend.web.VersionedResponse;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * US-25-04: an account's dated opening balance (FR-REC-007) - one per account, so a singleton
 * resource under the account. Rules in {@link OpeningBalanceService}.
 */
@RestController
@RequestMapping("/api/v1/accounts/{accountId}/opening-balance")
public class OpeningBalanceController {

  private final OpeningBalanceService openingBalanceService;

  public OpeningBalanceController(OpeningBalanceService openingBalanceService) {
    this.openingBalanceService = openingBalanceService;
  }

  @GetMapping
  public ResponseEntity<OpeningBalanceResponse> getOpeningBalance(
      @PathVariable UUID accountId, @AuthenticationPrincipal AuthenticatedUserPrincipal actor) {
    OpeningBalanceResponse response = openingBalanceService.get(accountId, actor);
    return VersionedResponse.ok(response, response.version());
  }

  @PostMapping
  public ResponseEntity<OpeningBalanceResponse> recordOpeningBalance(
      @PathVariable UUID accountId,
      @Valid @RequestBody OpeningBalanceRequest request,
      @AuthenticationPrincipal AuthenticatedUserPrincipal actor) {
    OpeningBalanceResponse response = openingBalanceService.record(accountId, request, actor);
    return VersionedResponse.created(response, response.version());
  }

  @PutMapping
  public ResponseEntity<OpeningBalanceResponse> replaceOpeningBalance(
      @PathVariable UUID accountId,
      @RequestHeader(value = IfMatchVersionParser.HEADER, required = false) String ifMatch,
      @Valid @RequestBody OpeningBalanceRequest request,
      @AuthenticationPrincipal AuthenticatedUserPrincipal actor) {
    OpeningBalanceResponse response =
        openingBalanceService.replace(
            accountId, request, IfMatchVersionParser.parse(ifMatch), actor);
    return VersionedResponse.ok(response, response.version());
  }

  @DeleteMapping
  public ResponseEntity<Void> deleteOpeningBalance(
      @PathVariable UUID accountId,
      @RequestHeader(value = IfMatchVersionParser.HEADER, required = false) String ifMatch,
      @AuthenticationPrincipal AuthenticatedUserPrincipal actor) {
    openingBalanceService.delete(accountId, IfMatchVersionParser.parse(ifMatch), actor);
    return ResponseEntity.noContent().build();
  }
}
