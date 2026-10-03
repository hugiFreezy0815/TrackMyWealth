package com.trackmywealth.backend.controller;

import com.trackmywealth.backend.dto.ReconciliationResultResponse;
import com.trackmywealth.backend.security.AuthenticatedUserPrincipal;
import com.trackmywealth.backend.service.ReconciliationService;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * US-25-02: detailed reconciliation history. A {@code BALANCE_ONLY} grant gets the account-level
 * status through {@code GET /accounts/{id}}; this endpoint requires {@code READ}.
 */
@RestController
@RequestMapping("/api/v1/accounts/{accountId}/reconciliations")
public class ReconciliationController {

  private final ReconciliationService reconciliationService;

  public ReconciliationController(ReconciliationService reconciliationService) {
    this.reconciliationService = reconciliationService;
  }

  @GetMapping
  public Page<ReconciliationResultResponse> list(
      @PathVariable UUID accountId,
      Pageable pageable,
      @AuthenticationPrincipal AuthenticatedUserPrincipal actor) {
    return reconciliationService.list(accountId, pageable, actor);
  }
}
