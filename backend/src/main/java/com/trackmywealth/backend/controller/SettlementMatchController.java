package com.trackmywealth.backend.controller;

import com.trackmywealth.backend.dto.SetSettlementSourceRequest;
import com.trackmywealth.backend.dto.SettlementMatchResponse;
import com.trackmywealth.backend.dto.SettlementSourceResponse;
import com.trackmywealth.backend.security.AuthenticatedUserPrincipal;
import com.trackmywealth.backend.service.SettlementMatchService;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
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
  public SettlementSourceResponse setSettlementSource(
      @PathVariable UUID cardAccountId,
      @Valid @RequestBody SetSettlementSourceRequest request,
      @AuthenticationPrincipal AuthenticatedUserPrincipal actor) {
    return settlementMatchService.setSettlementSource(cardAccountId, request, actor);
  }

  @GetMapping("/accounts/{cardAccountId}/settlement-source")
  public SettlementSourceResponse getSettlementSource(
      @PathVariable UUID cardAccountId, @AuthenticationPrincipal AuthenticatedUserPrincipal actor) {
    return settlementMatchService.getSettlementSource(cardAccountId, actor);
  }

  /** Runs matching for the card now; idempotent. Returns every match the card has. */
  @PostMapping("/accounts/{cardAccountId}/settlement-matches/run")
  public List<SettlementMatchResponse> run(
      @PathVariable UUID cardAccountId, @AuthenticationPrincipal AuthenticatedUserPrincipal actor) {
    return settlementMatchService.run(cardAccountId, actor);
  }

  /** Matches needing a decision by default ({@code status=PROPOSED}); newest first, at most 200. */
  @GetMapping("/settlement-matches")
  public List<SettlementMatchResponse> list(
      @RequestParam(required = false) String status,
      @AuthenticationPrincipal AuthenticatedUserPrincipal actor) {
    return settlementMatchService.list(status, actor);
  }

  @PostMapping("/settlement-matches/{matchId}/confirm")
  public SettlementMatchResponse confirm(
      @PathVariable UUID matchId, @AuthenticationPrincipal AuthenticatedUserPrincipal actor) {
    return settlementMatchService.confirm(matchId, actor);
  }

  @PostMapping("/settlement-matches/{matchId}/reject")
  public SettlementMatchResponse reject(
      @PathVariable UUID matchId, @AuthenticationPrincipal AuthenticatedUserPrincipal actor) {
    return settlementMatchService.reject(matchId, actor);
  }
}
