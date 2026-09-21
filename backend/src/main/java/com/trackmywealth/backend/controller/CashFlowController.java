package com.trackmywealth.backend.controller;

import com.trackmywealth.backend.dto.CashFlowResponse;
import com.trackmywealth.backend.security.AuthenticatedUserPrincipal;
import com.trackmywealth.backend.service.CashFlowService;
import java.time.YearMonth;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** US-09-02: thin, partial monthly spending - superseded by EPIC 10's cash flow. */
@RestController
@RequestMapping("/api/v1/cash-flow")
public class CashFlowController {

  private final CashFlowService cashFlowService;

  public CashFlowController(CashFlowService cashFlowService) {
    this.cashFlowService = cashFlowService;
  }

  /** {@code month} is {@code yyyy-MM}. */
  @GetMapping
  public CashFlowResponse getCashFlow(
      @RequestParam YearMonth month, @AuthenticationPrincipal AuthenticatedUserPrincipal actor) {
    return cashFlowService.getCashFlow(month, actor);
  }
}
