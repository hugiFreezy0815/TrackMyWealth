package com.trackmywealth.backend.controller;

import com.trackmywealth.backend.dto.CreateLoanRepaymentRequest;
import com.trackmywealth.backend.dto.LoanRepaymentResponse;
import com.trackmywealth.backend.security.AuthenticatedUserPrincipal;
import com.trackmywealth.backend.service.LoanRepaymentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * US-10-02/FR-CF-006: payments to a loan or mortgage, each split into interest (an expense) and
 * principal (a balance-sheet movement). Rules in {@link LoanRepaymentService}. A payment is
 * removed, restored or re-described through any of its ledger rows ({@code /transactions}).
 */
@RestController
@RequestMapping("/api/v1/accounts/{accountId}/repayments")
@Tag(name = "Loans")
public class LoanRepaymentController {

  private final LoanRepaymentService loanRepaymentService;

  public LoanRepaymentController(LoanRepaymentService loanRepaymentService) {
    this.loanRepaymentService = loanRepaymentService;
  }

  @Operation(operationId = "recordLoanRepayment", summary = "Record a loan repayment")
  @PostMapping
  public ResponseEntity<LoanRepaymentResponse> recordRepayment(
      @PathVariable UUID accountId,
      @Valid @RequestBody CreateLoanRepaymentRequest request,
      @AuthenticationPrincipal AuthenticatedUserPrincipal actor) {
    return ResponseEntity.status(HttpStatus.CREATED)
        .body(loanRepaymentService.record(accountId, request, actor));
  }

  @Operation(operationId = "listLoanRepayments", summary = "List a loan's repayments")
  @GetMapping
  public List<LoanRepaymentResponse> listRepayments(
      @PathVariable UUID accountId, @AuthenticationPrincipal AuthenticatedUserPrincipal actor) {
    return loanRepaymentService.list(accountId, actor);
  }
}
