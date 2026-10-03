package com.trackmywealth.backend.controller;

import com.trackmywealth.backend.dto.AccountValuation;
import com.trackmywealth.backend.security.AuthenticatedUserPrincipal;
import com.trackmywealth.backend.service.AccountValuationService;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * US-09-01 (FR-CC-001/003): an account's current balance, from the shared DM-17 valuation path.
 * Separate from {@link TransactionController} because a balance is an account-level figure that
 * every account type with a value source will have, not something only the ledger endpoints own.
 *
 * <p>US-25-04: an optional {@code asOf} (ISO date, not in the future) reads the balance as it stood
 * on that day - before an account's opening balance, it is unknown, not zero. A past date needs
 * {@code READ}, not just {@code BALANCE_ONLY}: balances on consecutive days reveal the transactions
 * between them.
 *
 * <p>"Today" is the server's business date ({@code app.business-zone}), not the client's. A client
 * wanting the current balance omits {@code asOf} rather than sending its own local date: ahead of
 * the business zone that date is a 422 (future), behind it a past date that needs {@code READ}.
 *
 * <p>US-06-05: {@code currency} shows it in another currency, at the valuation date's rate.
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
      @PathVariable UUID accountId,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOf,
      @RequestParam(required = false) String currency,
      @AuthenticationPrincipal AuthenticatedUserPrincipal actor) {
    return accountValuationService.getBalance(accountId, asOf, actor, currency);
  }
}
