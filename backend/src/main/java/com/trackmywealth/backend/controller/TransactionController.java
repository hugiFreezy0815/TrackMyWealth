package com.trackmywealth.backend.controller;

import com.trackmywealth.backend.dto.AccountValuation;
import com.trackmywealth.backend.dto.CreateTransactionRequest;
import com.trackmywealth.backend.dto.TransactionResponse;
import com.trackmywealth.backend.security.AuthenticatedUserPrincipal;
import com.trackmywealth.backend.service.AccountValuationService;
import com.trackmywealth.backend.service.TransactionService;
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
 * US-09-01 (credit-card purchases and the resulting balance), gated per account by {@code
 * AccessControlService} (US-03-03).
 */
@RestController
@RequestMapping("/api/v1/accounts/{accountId}")
public class TransactionController {

  private final TransactionService transactionService;
  private final AccountValuationService accountValuationService;

  public TransactionController(
      TransactionService transactionService, AccountValuationService accountValuationService) {
    this.transactionService = transactionService;
    this.accountValuationService = accountValuationService;
  }

  @PostMapping("/transactions")
  public ResponseEntity<TransactionResponse> recordTransaction(
      @PathVariable UUID accountId,
      @Valid @RequestBody CreateTransactionRequest request,
      @AuthenticationPrincipal AuthenticatedUserPrincipal actor) {
    return ResponseEntity.status(HttpStatus.CREATED)
        .body(transactionService.recordTransaction(accountId, request, actor));
  }

  @GetMapping("/transactions")
  public List<TransactionResponse> listTransactions(
      @PathVariable UUID accountId, @AuthenticationPrincipal AuthenticatedUserPrincipal actor) {
    return transactionService.listTransactions(accountId, actor);
  }

  @GetMapping("/balance")
  public AccountValuation getBalance(
      @PathVariable UUID accountId, @AuthenticationPrincipal AuthenticatedUserPrincipal actor) {
    return accountValuationService.getBalance(accountId, actor);
  }
}
