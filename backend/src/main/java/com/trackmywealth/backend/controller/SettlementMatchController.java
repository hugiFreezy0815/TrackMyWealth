package com.trackmywealth.backend.controller;

import com.trackmywealth.backend.dto.PageResponse;
import com.trackmywealth.backend.dto.SetSettlementSourceRequest;
import com.trackmywealth.backend.dto.SettlementMatchResponse;
import com.trackmywealth.backend.dto.SettlementMatchRunResponse;
import com.trackmywealth.backend.dto.SettlementMatchValues;
import com.trackmywealth.backend.dto.SettlementSourceResponse;
import com.trackmywealth.backend.error.ProblemCode;
import com.trackmywealth.backend.security.AuthenticatedUserPrincipal;
import com.trackmywealth.backend.service.SettlementMatchService;
import com.trackmywealth.backend.web.IfMatchVersionParser;
import com.trackmywealth.backend.web.Paged;
import com.trackmywealth.backend.web.Paged.SortField;
import com.trackmywealth.backend.web.ProblemCodes;
import com.trackmywealth.backend.web.ResponseHeader;
import com.trackmywealth.backend.web.SendsHeaders;
import com.trackmywealth.backend.web.VersionedResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
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
  @SendsHeaders(ResponseHeader.ETAG)
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
  @SendsHeaders(ResponseHeader.ETAG)
  public ResponseEntity<SettlementSourceResponse> getSettlementSource(
      @PathVariable UUID cardAccountId, @AuthenticationPrincipal AuthenticatedUserPrincipal actor) {
    SettlementSourceResponse response =
        settlementMatchService.getSettlementSource(cardAccountId, actor);
    return VersionedResponse.ok(response, response.version());
  }

  /** Runs matching for the card now; idempotent and returns only a bounded summary. */
  @Operation(
      operationId = "runSettlementMatching",
      summary = "Match card payments to statements now")
  @ProblemCodes({ProblemCode.UNPROCESSABLE})
  @PostMapping("/accounts/{cardAccountId}/settlement-matches/run")
  public SettlementMatchRunResponse run(
      @PathVariable UUID cardAccountId, @AuthenticationPrincipal AuthenticatedUserPrincipal actor) {
    return settlementMatchService.run(cardAccountId, actor);
  }

  /** Paginated settlement matches, filtered before counting to avoid leaking inaccessible rows. */
  @Operation(
      operationId = "listSettlementMatches",
      summary = "List settlement matches",
      description = "Matches of one status, newest first; by default those needing a decision.")
  @ProblemCodes({ProblemCode.NOT_FOUND})
  @GetMapping("/settlement-matches")
  public PageResponse<SettlementMatchResponse> list(
      @Parameter(
              description = "The status to list.",
              schema =
                  @Schema(
                      allowableValues = {
                        SettlementMatchValues.PROPOSED,
                        SettlementMatchValues.CONFIRMED,
                        SettlementMatchValues.REJECTED
                      },
                      defaultValue = SettlementMatchValues.PROPOSED))
          @RequestParam(required = false)
          String status,
      @Parameter(
              description = "Lists one kind of match only; both kinds when absent.",
              schema =
                  @Schema(
                      allowableValues = {
                        SettlementMatchValues.CARD_SETTLEMENT,
                        SettlementMatchValues.TRANSFER
                      }))
          @RequestParam(required = false)
          String kind,
      @Parameter(description = "Only matches belonging to this card or credited account.")
          @RequestParam(required = false)
          UUID cardAccountId,
      @Paged(sort = @SortField(name = "createdAt"), defaultSort = "createdAt,desc")
          Pageable pageable,
      @AuthenticationPrincipal AuthenticatedUserPrincipal actor) {
    return PageResponse.of(
        settlementMatchService.list(status, kind, cardAccountId, pageable, actor));
  }

  @Operation(operationId = "confirmSettlementMatch", summary = "Confirm a settlement match")
  @ProblemCodes({ProblemCode.CONFLICT})
  @PostMapping("/settlement-matches/{matchId}/confirm")
  @SendsHeaders(ResponseHeader.ETAG)
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
  @SendsHeaders(ResponseHeader.ETAG)
  public ResponseEntity<SettlementMatchResponse> reject(
      @PathVariable UUID matchId,
      @RequestHeader(value = IfMatchVersionParser.HEADER, required = false) String ifMatch,
      @AuthenticationPrincipal AuthenticatedUserPrincipal actor) {
    SettlementMatchResponse response =
        settlementMatchService.reject(matchId, IfMatchVersionParser.parse(ifMatch), actor);
    return VersionedResponse.ok(response, response.version());
  }
}
