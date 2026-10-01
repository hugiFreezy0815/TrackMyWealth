package com.trackmywealth.backend.controller;

import com.trackmywealth.backend.dto.SetSettlementSourceRequest;
import com.trackmywealth.backend.dto.SettlementMatchResponse;
import com.trackmywealth.backend.dto.SettlementSourceResponse;
import com.trackmywealth.backend.security.AuthenticatedUserPrincipal;
import com.trackmywealth.backend.service.SettlementMatchService;
import com.trackmywealth.backend.web.IfMatchVersionParser;
import com.trackmywealth.backend.web.VersionedResponse;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * US-09-02 (credit-card settlement matching), gated per account by {@code AccessControlService}
 * (US-03-03): deciding a match needs {@code EDIT} on both the card and its paying account.
 */
@RestController
@RequestMapping("/api/v1")
public class SettlementMatchController {

  private final SettlementMatchService settlementMatchService;

  public SettlementMatchController(SettlementMatchService settlementMatchService) {
    this.settlementMatchService = settlementMatchService;
  }

  @PutMapping("/accounts/{cardAccountId}/settlement-source")
  public ResponseEntity<SettlementSourceResponse> setSettlementSource(
      @PathVariable UUID cardAccountId,
      @RequestHeader(value = IfMatchVersionParser.HEADER, required = false) String ifMatch,
      @Valid @RequestBody SetSettlementSourceRequest request,
      @AuthenticationPrincipal AuthenticatedUserPrincipal actor) {
    SettlementSourceResponse response =
        settlementMatchService.setSettlementSource(
            cardAccountId, request, IfMatchVersionParser.parse(ifMatch), actor);
    return VersionedResponse.ok(response, response.version());
  }

  @GetMapping("/accounts/{cardAccountId}/settlement-source")
  public ResponseEntity<SettlementSourceResponse> getSettlementSource(
      @PathVariable UUID cardAccountId, @AuthenticationPrincipal AuthenticatedUserPrincipal actor) {
    SettlementSourceResponse response =
        settlementMatchService.getSettlementSource(cardAccountId, actor);
    return VersionedResponse.ok(response, response.version());
  }

  /** Runs matching for the card now; idempotent. Returns every match the card has. */
  @PostMapping("/accounts/{cardAccountId}/settlement-matches/run")
  public List<SettlementMatchResponse> run(
      @PathVariable UUID cardAccountId, @AuthenticationPrincipal AuthenticatedUserPrincipal actor) {
    return settlementMatchService.run(cardAccountId, actor);
  }

  /**
   * Matches needing a decision by default ({@code status=PROPOSED}); newest first, at most 200.
   * {@code kind} ({@code CARD_SETTLEMENT} or {@code TRANSFER}, US-10-01) narrows the list to one
   * kind of match; without it both are listed.
   */
  @GetMapping("/settlement-matches")
  public List<SettlementMatchResponse> list(
      @RequestParam(required = false) String status,
      @RequestParam(required = false) String kind,
      @AuthenticationPrincipal AuthenticatedUserPrincipal actor) {
    return settlementMatchService.list(status, kind, actor);
  }

  @PostMapping("/settlement-matches/{matchId}/confirm")
  public ResponseEntity<SettlementMatchResponse> confirm(
      @PathVariable UUID matchId,
      @RequestHeader(value = IfMatchVersionParser.HEADER, required = false) String ifMatch,
      @AuthenticationPrincipal AuthenticatedUserPrincipal actor) {
    SettlementMatchResponse response =
        settlementMatchService.confirm(matchId, IfMatchVersionParser.parse(ifMatch), actor);
    return VersionedResponse.ok(response, response.version());
  }

  @PostMapping("/settlement-matches/{matchId}/reject")
  public ResponseEntity<SettlementMatchResponse> reject(
      @PathVariable UUID matchId,
      @RequestHeader(value = IfMatchVersionParser.HEADER, required = false) String ifMatch,
      @AuthenticationPrincipal AuthenticatedUserPrincipal actor) {
    SettlementMatchResponse response =
        settlementMatchService.reject(matchId, IfMatchVersionParser.parse(ifMatch), actor);
    return VersionedResponse.ok(response, response.version());
  }
}
