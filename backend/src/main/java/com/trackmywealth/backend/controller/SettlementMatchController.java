package com.trackmywealth.backend.controller;

import com.trackmywealth.backend.dto.SetSettlementSourceRequest;
import com.trackmywealth.backend.dto.SettlementMatchResponse;
import com.trackmywealth.backend.dto.SettlementSourceResponse;
import com.trackmywealth.backend.error.ProblemCode;
import com.trackmywealth.backend.security.AuthenticatedUserPrincipal;
import com.trackmywealth.backend.service.SettlementMatchService;
import com.trackmywealth.backend.web.IfMatchVersionParser;
import com.trackmywealth.backend.web.ProblemCodes;
import com.trackmywealth.backend.web.VersionedResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
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
 * US-09-02 (credit-card settlement matching): deciding a match needs membership of the workspace of
 * both the card and its paying account ({@code AccessControlService}).
 */
@RestController
@RequestMapping("/api/v1")
@Tag(name = "Credit cards")
public class SettlementMatchController {

  private final SettlementMatchService settlementMatchService;

  public SettlementMatchController(SettlementMatchService settlementMatchService) {
    this.settlementMatchService = settlementMatchService;
  }

  @Operation(operationId = "setSettlementSource", summary = "Set the account a card is paid from")
  @ProblemCodes({ProblemCode.ACCOUNT_ARCHIVED, ProblemCode.UNPROCESSABLE})
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

  @Operation(operationId = "getSettlementSource", summary = "Read the account a card is paid from")
  @ProblemCodes({ProblemCode.UNPROCESSABLE})
  @GetMapping("/accounts/{cardAccountId}/settlement-source")
  public ResponseEntity<SettlementSourceResponse> getSettlementSource(
      @PathVariable UUID cardAccountId, @AuthenticationPrincipal AuthenticatedUserPrincipal actor) {
    SettlementSourceResponse response =
        settlementMatchService.getSettlementSource(cardAccountId, actor);
    return VersionedResponse.ok(response, response.version());
  }

  /** Runs matching for the card now; idempotent. Returns every match the card has. */
  @Operation(
      operationId = "runSettlementMatching",
      summary = "Match card payments to statements now")
  @ProblemCodes({ProblemCode.UNPROCESSABLE})
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
  @Operation(operationId = "listSettlementMatches", summary = "List settlement matches")
  @ProblemCodes({ProblemCode.NOT_FOUND})
  @GetMapping("/settlement-matches")
  public List<SettlementMatchResponse> list(
      @RequestParam(required = false) String status,
      @RequestParam(required = false) String kind,
      @AuthenticationPrincipal AuthenticatedUserPrincipal actor) {
    return settlementMatchService.list(status, kind, actor);
  }

  @Operation(operationId = "confirmSettlementMatch", summary = "Confirm a settlement match")
  @ProblemCodes({ProblemCode.CONFLICT})
  @PostMapping("/settlement-matches/{matchId}/confirm")
  public ResponseEntity<SettlementMatchResponse> confirm(
      @PathVariable UUID matchId,
      @RequestHeader(value = IfMatchVersionParser.HEADER, required = false) String ifMatch,
      @AuthenticationPrincipal AuthenticatedUserPrincipal actor) {
    SettlementMatchResponse response =
        settlementMatchService.confirm(matchId, IfMatchVersionParser.parse(ifMatch), actor);
    return VersionedResponse.ok(response, response.version());
  }

  @Operation(operationId = "rejectSettlementMatch", summary = "Reject a settlement match")
  @ProblemCodes({ProblemCode.CONFLICT})
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
