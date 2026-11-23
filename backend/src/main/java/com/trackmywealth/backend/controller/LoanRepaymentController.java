package com.trackmywealth.backend.controller;

import com.trackmywealth.backend.dto.CreateLoanRepaymentRequest;
import com.trackmywealth.backend.dto.LoanRepaymentResponse;
import com.trackmywealth.backend.dto.LoanRepaymentYearlyTotalsResponse;
import com.trackmywealth.backend.dto.PageResponse;
import com.trackmywealth.backend.error.ProblemCode;
import com.trackmywealth.backend.security.AuthenticatedUserPrincipal;
import com.trackmywealth.backend.service.IdempotentCreateService;
import com.trackmywealth.backend.service.LoanRepaymentService;
import com.trackmywealth.backend.web.Paged;
import com.trackmywealth.backend.web.Paged.SortField;
import com.trackmywealth.backend.web.ProblemCodes;
import com.trackmywealth.backend.web.SuccessStatus;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
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
 * US-10-02/FR-CF-006: payments to a loan or mortgage, each split into interest (an expense) and
 * principal (a balance-sheet movement). Rules in {@link LoanRepaymentService}. A payment is
 * removed, restored or re-described through any of its ledger rows ({@code /transactions}).
 */
@RestController
@RequestMapping("/api/v1/accounts/{accountId}/repayments")
@Tag(name = "Loans")
public class LoanRepaymentController {

  private final LoanRepaymentService loanRepaymentService;
  private final IdempotentCreateService idempotentCreates;

  public LoanRepaymentController(
      LoanRepaymentService loanRepaymentService, IdempotentCreateService idempotentCreates) {
    this.loanRepaymentService = loanRepaymentService;
    this.idempotentCreates = idempotentCreates;
  }

  @SuccessStatus(HttpStatus.CREATED)
  @Operation(operationId = "recordLoanRepayment", summary = "Record a loan repayment")
  @ProblemCodes({
    ProblemCode.CONFLICT,
    ProblemCode.ACCOUNT_ARCHIVED,
    ProblemCode.UNPROCESSABLE,
    ProblemCode.IDEMPOTENCY_KEY_IN_USE,
    ProblemCode.IDEMPOTENCY_KEY_MISMATCH
  })
  @PostMapping
  public ResponseEntity<LoanRepaymentResponse> recordRepayment(
      @PathVariable UUID accountId,
      @Valid @RequestBody CreateLoanRepaymentRequest request,
      @Parameter(
              description = IdempotentCreateService.HEADER_DESCRIPTION,
              schema = @Schema(type = "string", format = "uuid"))
          @RequestHeader(value = IdempotentCreateService.HEADER, required = false)
          String idempotencyKey,
      @AuthenticationPrincipal AuthenticatedUserPrincipal actor) {
    return ResponseEntity.status(HttpStatus.CREATED)
        .body(
            idempotentCreates.create(
                "recordLoanRepayment",
                idempotencyKey,
                actor,
                List.of(accountId, request),
                LoanRepaymentResponse.class,
                () -> loanRepaymentService.record(accountId, request, actor)));
  }

  @Operation(operationId = "listLoanRepayments", summary = "List a loan's repayments")
  @GetMapping
  public PageResponse<LoanRepaymentResponse> listRepayments(
      @PathVariable UUID accountId,
      @Paged(sort = @SortField(name = "bookingDate"), defaultSort = "bookingDate,desc")
          Pageable pageable,
      @AuthenticationPrincipal AuthenticatedUserPrincipal actor) {
    return PageResponse.of(loanRepaymentService.list(accountId, pageable, actor));
  }

  @Operation(
      operationId = "getLoanRepaymentYearlyTotals",
      summary = "Sum a loan's repayments per calendar year",
      description =
          "Principal and interest paid per calendar year of booking date, newest year first, from"
              + " the repayments the list returns. A payment with an unknown split adds to"
              + " amountPaid and unknownSplitAmount only; principalPaid and interestPaid are null"
              + " when no payment of the year has a known split. splitEstimated flags a year with"
              + " any estimated split.")
  @GetMapping("/yearly-totals")
  public LoanRepaymentYearlyTotalsResponse getYearlyTotals(
      @PathVariable UUID accountId, @AuthenticationPrincipal AuthenticatedUserPrincipal actor) {
    return loanRepaymentService.yearlyTotals(accountId, actor);
  }
}
