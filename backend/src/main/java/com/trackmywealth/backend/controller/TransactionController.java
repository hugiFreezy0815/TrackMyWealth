package com.trackmywealth.backend.controller;

import com.trackmywealth.backend.dto.CreateTransactionRequest;
import com.trackmywealth.backend.dto.TransactionResponse;
import com.trackmywealth.backend.security.AuthenticatedUserPrincipal;
import com.trackmywealth.backend.service.TransactionService;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
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
 * US-09-01 (credit-card purchases), gated per account by {@code AccessControlService} (US-03-03).
 * The resulting balance is read from {@link AccountBalanceController}.
 */
@RestController
@RequestMapping("/api/v1/accounts/{accountId}")
public class TransactionController {

  private final TransactionService transactionService;

  public TransactionController(TransactionService transactionService) {
    this.transactionService = transactionService;
  }

  @PostMapping("/transactions")
  public ResponseEntity<TransactionResponse> recordTransaction(
      @PathVariable UUID accountId,
      @Valid @RequestBody CreateTransactionRequest request,
      @AuthenticationPrincipal AuthenticatedUserPrincipal actor) {
    return ResponseEntity.status(HttpStatus.CREATED)
        .body(transactionService.recordTransaction(accountId, request, actor));
  }

  /** Newest booking first; the order is fixed server-side, only {@code page}/{@code size} apply. */
  @GetMapping("/transactions")
  public Page<TransactionResponse> listTransactions(
      @PathVariable UUID accountId,
      @PageableDefault(size = 50) Pageable pageable,
      @AuthenticationPrincipal AuthenticatedUserPrincipal actor) {
    return transactionService.listTransactions(accountId, pageable, actor);
  }
}
