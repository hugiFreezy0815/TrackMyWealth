package com.trackmywealth.backend.controller;

import com.trackmywealth.backend.dto.ReconciliationDecisionRequest;
import com.trackmywealth.backend.dto.ReconciliationResultResponse;
import com.trackmywealth.backend.security.AuthenticatedUserPrincipal;
import com.trackmywealth.backend.service.ReconciliationDecisionService;
import com.trackmywealth.backend.service.ReconciliationHistoryService;
import com.trackmywealth.backend.web.IfMatchVersionParser;
import com.trackmywealth.backend.web.VersionedResponse;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * US-25-02: detailed reconciliation history. A {@code BALANCE_ONLY} grant gets the account-level
 * status through {@code GET /accounts/{id}}; this endpoint requires {@code READ}.
 *
 * <p>US-25-03: a member accepts, dismisses or reopens one result; rules in {@link
 * ReconciliationDecisionService}.
 */
@RestController
@RequestMapping("/api/v1/accounts/{accountId}/reconciliations")
public class ReconciliationController {

  private final ReconciliationHistoryService historyService;
  private final ReconciliationDecisionService reconciliationDecisionService;

  public ReconciliationController(
      ReconciliationHistoryService historyService,
      ReconciliationDecisionService reconciliationDecisionService) {
    this.historyService = historyService;
    this.reconciliationDecisionService = reconciliationDecisionService;
  }

  @GetMapping
  public Page<ReconciliationResultResponse> list(
      @PathVariable UUID accountId,
      Pageable pageable,
      @AuthenticationPrincipal AuthenticatedUserPrincipal actor) {
    return historyService.list(accountId, pageable, actor);
  }

  @GetMapping("/{resultId}")
  public ResponseEntity<ReconciliationResultResponse> get(
      @PathVariable UUID accountId,
      @PathVariable UUID resultId,
      @AuthenticationPrincipal AuthenticatedUserPrincipal actor) {
    ReconciliationResultResponse response = historyService.get(accountId, resultId, actor);
    return VersionedResponse.ok(response, response.version());
  }

  @PostMapping("/{resultId}/accept")
  public ResponseEntity<ReconciliationResultResponse> accept(
      @PathVariable UUID accountId,
      @PathVariable UUID resultId,
      @RequestHeader(value = IfMatchVersionParser.HEADER, required = false) String ifMatch,
      @Valid @RequestBody ReconciliationDecisionRequest request,
      @AuthenticationPrincipal AuthenticatedUserPrincipal actor) {
    ReconciliationResultResponse response =
        reconciliationDecisionService.accept(
            accountId, resultId, request.note(), IfMatchVersionParser.parse(ifMatch), actor);
    return VersionedResponse.ok(response, response.version());
  }

  @PostMapping("/{resultId}/dismiss")
  public ResponseEntity<ReconciliationResultResponse> dismiss(
      @PathVariable UUID accountId,
      @PathVariable UUID resultId,
      @RequestHeader(value = IfMatchVersionParser.HEADER, required = false) String ifMatch,
      @Valid @RequestBody ReconciliationDecisionRequest request,
      @AuthenticationPrincipal AuthenticatedUserPrincipal actor) {
    ReconciliationResultResponse response =
        reconciliationDecisionService.dismiss(
            accountId, resultId, request.note(), IfMatchVersionParser.parse(ifMatch), actor);
    return VersionedResponse.ok(response, response.version());
  }

  @PostMapping("/{resultId}/reopen")
  public ResponseEntity<ReconciliationResultResponse> reopen(
      @PathVariable UUID accountId,
      @PathVariable UUID resultId,
      @RequestHeader(value = IfMatchVersionParser.HEADER, required = false) String ifMatch,
      @AuthenticationPrincipal AuthenticatedUserPrincipal actor) {
    ReconciliationResultResponse response =
        reconciliationDecisionService.reopen(
            accountId, resultId, IfMatchVersionParser.parse(ifMatch), actor);
    return VersionedResponse.ok(response, response.version());
  }
}
