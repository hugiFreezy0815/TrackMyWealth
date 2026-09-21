package com.trackmywealth.backend.controller;

import com.trackmywealth.backend.dto.AccountValuation;
import com.trackmywealth.backend.security.AuthenticatedUserPrincipal;
import com.trackmywealth.backend.service.AccountValuationService;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * US-09-01 (FR-CC-001/003): an account's current balance, from the shared DM-17 valuation path.
 * Separate from {@link TransactionController} because a balance is an account-level figure that
 * every account type with a value source will have, not something only the ledger endpoints own.
 */
@RestController
@RequestMapping("/api/v1/accounts/{accountId}/balance")
public class AccountBalanceController {

  private final AccountValuationService accountValuationService;

  public AccountBalanceController(AccountValuationService accountValuationService) {
    this.accountValuationService = accountValuationService;
  }

  @GetMapping
  public AccountValuation getBalance(
      @PathVariable UUID accountId, @AuthenticationPrincipal AuthenticatedUserPrincipal actor) {
    return accountValuationService.getBalance(accountId, actor);
  }
}
