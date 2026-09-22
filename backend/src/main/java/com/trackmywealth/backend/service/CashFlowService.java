package com.trackmywealth.backend.service;

import com.trackmywealth.backend.dto.AccessLevelValues;
import com.trackmywealth.backend.dto.CashFlowResponse;
import com.trackmywealth.backend.dto.CashFlowResponse.CurrencyAmount;
import com.trackmywealth.backend.entity.Account;
import com.trackmywealth.backend.repository.AccountRepository;
import com.trackmywealth.backend.repository.TransactionRepository;
import com.trackmywealth.backend.security.AuthenticatedUserPrincipal;
import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * US-09-02: the thin, partial monthly spending view that makes "a card settlement is never counted
 * a second time" observable (FR-CC-005, RULE-007/009). It is the spending half of what EPIC 10's
 * cash-flow will be, and is superseded by it.
 *
 * <p>Spending is card purchases and withdrawals, summed per currency over the month's booking dates
 * - never the settlement date (FR-CC-009). What it leaves out is what makes it correct:
 *
 * <ul>
 *   <li>{@code SETTLEMENT} rows, which are never spending, matched or not;
 *   <li>any row flagged an internal transfer (a matched payment, DM-05);
 *   <li>a payment awaiting a settlement decision, which is reported as {@code pendingReview}
 *       instead - neither counted as spending nor silently dropped (FR-CF-004/005 data-quality
 *       rule).
 * </ul>
 *
 * <p>Only accounts the caller may {@code READ} contribute: transaction-level detail is not shown at
 * {@code BALANCE_ONLY}.
 */
@Service
public class CashFlowService {

  // Withdrawal is the only way to record money leaving an ordinary account yet (EXPENSE and the
  // rest arrive with US-07-01), so it stands in for "uncategorised outgoing" here. FEE (US-09-04):
  // a disclosed foreign-transaction fee is a real cost the member incurred, not folded into the
  // purchase amount - the story's own purpose ("see the true cost including any FX fee") is exactly
  // this: it must show up in spending on its own.
  private static final Set<String> SPENDING_TYPES =
      Set.of("CREDIT_CARD_PURCHASE", "WITHDRAWAL", "FEE");

  private final AccessControlService accessControlService;
  private final AccountRepository accountRepository;
  private final TransactionRepository transactionRepository;

  public CashFlowService(
      AccessControlService accessControlService,
      AccountRepository accountRepository,
      TransactionRepository transactionRepository) {
    this.accessControlService = accessControlService;
    this.accountRepository = accountRepository;
    this.transactionRepository = transactionRepository;
  }

  @Transactional(readOnly = true)
  public CashFlowResponse getCashFlow(YearMonth month, AuthenticatedUserPrincipal actor) {
    UUID memberId = accessControlService.requireActingMember(actor);
    List<UUID> accountIds =
        accessControlService
            .accountsWithAccess(
                memberId,
                accountRepository.findByWorkspaceId(actor.workspaceId()),
                AccessLevelValues.READ)
            .stream()
            .map(Account::getId)
            .toList();
    if (accountIds.isEmpty()) {
      return new CashFlowResponse(month.toString(), List.of(), List.of(), true);
    }

    // Both queries sum signed, cash-direction amounts (money out is negative), so a spend is the
    // negated sum and a void's reversing row nets against its original.
    List<CurrencyAmount> spending =
        asSpent(
            transactionRepository.sumSpendingByCurrency(
                accountIds, SPENDING_TYPES, month.atDay(1), month.atEndOfMonth()));
    List<CurrencyAmount> pending =
        asSpent(
            transactionRepository.sumUnresolvedSettlementsByCurrency(
                accountIds, month.atDay(1), month.atEndOfMonth()));
    return new CashFlowResponse(month.toString(), spending, pending, pending.isEmpty());
  }

  private static List<CurrencyAmount> asSpent(List<Object[]> rows) {
    return rows.stream()
        .map(row -> new CurrencyAmount((String) row[0], ((BigDecimal) row[1]).negate()))
        .toList();
  }
}
