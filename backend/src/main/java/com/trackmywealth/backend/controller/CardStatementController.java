package com.trackmywealth.backend.controller;

import com.trackmywealth.backend.dto.CardStatementResponse;
import com.trackmywealth.backend.dto.SetStatementConfigRequest;
import com.trackmywealth.backend.dto.StatementConfigResponse;
import com.trackmywealth.backend.security.AuthenticatedUserPrincipal;
import com.trackmywealth.backend.service.CardStatementService;
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

/**
 * US-09-03 (credit-card statement cycle), gated per card by {@code AccessControlService}
 * (US-03-03): configuring the cycle needs {@code EDIT}; reading it back or viewing the current
 * statement only needs {@code BALANCE_ONLY}, the same level {@link AccountBalanceController} uses.
 */
@RestController
@RequestMapping("/api/v1/accounts/{cardAccountId}")
public class CardStatementController {

  private final CardStatementService cardStatementService;

  public CardStatementController(CardStatementService cardStatementService) {
    this.cardStatementService = cardStatementService;
  }

  @PutMapping("/statement-config")
  public ResponseEntity<StatementConfigResponse> setStatementConfig(
      @PathVariable UUID cardAccountId,
      @RequestHeader(value = IfMatchVersionParser.HEADER, required = false) String ifMatch,
      @Valid @RequestBody SetStatementConfigRequest request,
      @AuthenticationPrincipal AuthenticatedUserPrincipal actor) {
    StatementConfigResponse response =
        cardStatementService.setStatementConfig(
            cardAccountId, request, IfMatchVersionParser.parse(ifMatch), actor);
    return VersionedResponse.ok(response, response.version());
  }

  @GetMapping("/statement-config")
  public ResponseEntity<StatementConfigResponse> getStatementConfig(
      @PathVariable UUID cardAccountId, @AuthenticationPrincipal AuthenticatedUserPrincipal actor) {
    StatementConfigResponse response =
        cardStatementService.getStatementConfig(cardAccountId, actor);
    return VersionedResponse.ok(response, response.version());
  }

  /**
   * The current statement (the most recently closed cycle as of today) - see the service Javadoc.
   */
  @GetMapping("/statement")
  public CardStatementResponse getCurrentStatement(
      @PathVariable UUID cardAccountId, @AuthenticationPrincipal AuthenticatedUserPrincipal actor) {
    return cardStatementService.getCurrentStatement(cardAccountId, actor);
  }
}
