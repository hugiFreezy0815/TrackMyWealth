package com.trackmywealth.backend.service;

import com.trackmywealth.backend.dto.AccessLevelValues;
import com.trackmywealth.backend.dto.CashFlowResponse;
import com.trackmywealth.backend.dto.CashFlowResponse.CurrencyAmount;
import com.trackmywealth.backend.entity.Account;
import com.trackmywealth.backend.repository.AccountRepository;
import com.trackmywealth.backend.repository.TransactionRepository;
import com.trackmywealth.backend.security.AuthenticatedUserPrincipal;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * US-10-01 (replacing US-09-02's spending-only view): one month's cash flow per currency, by
 * booking date - never a card's settlement date (FR-CC-009) - in four figures that never overlap
 * (FR-CF-001/002/003):
 *
 * <ul>
 *   <li><b>income</b> - {@code INCOME}, {@code INTEREST} and {@code DIVIDEND};
 *   <li><b>spending</b> - card purchases, withdrawals, expenses, fees and tax (consumption);
 *   <li><b>saving</b> - money moved from an account that does not count as saving into one that
 *       does ({@code counts_as_saving}, V41), less money moved back out; a transfer between two
 *       alike accounts is in no figure at all, and neither is a trade;
 *   <li><b>pendingReview</b> - what awaits a member's decision before it can count anywhere: an
 *       unresolved card settlement, a proposed transfer pair, an unlinked transfer leg.
 * </ul>
 *
 * <p>Left out entirely: any row flagged an internal transfer (a card settlement or own-account
 * transfer, DM-05) except as saving; a voided row with its reversal (US-07-02); a soft-deleted row.
 * {@code complete} is {@code false} while anything is pending review. The savings rate is US-10-05.
 *
 * <p>Only accounts the caller may {@code READ} contribute: transaction-level detail is not shown at
 * {@code BALANCE_ONLY}.
 */
@Service
public class CashFlowService {

  // Money out of an ordinary account (WITHDRAWAL, EXPENSE, TAX) or off a card
  // (CREDIT_CARD_PURCHASE),
  // plus FEE (US-09-04): a disclosed foreign-transaction fee is a real cost the member incurred,
  // not folded into the purchase amount - the story's own purpose ("see the true cost including any
  // FX fee") is exactly this: it must show up in spending on its own. A card payment typed EXPENSE
  // or WITHDRAWAL is kept out again by settlement matching (SettlementDetectionService), never
  // here.
  // INCOME, DEPOSIT, INTEREST and REFUND are inflows and deliberately not netted against spending.
  // US-10-01: what the household earns. A refund is not income (it undoes spending); a DEPOSIT is
  // money moved in, which is either a transfer or of unknown origin.
  private static final Set<String> INCOME_TYPES = Set.of("INCOME", "INTEREST", "DIVIDEND");

  private static final Set<String> SPENDING_TYPES =
      Set.of("CREDIT_CARD_PURCHASE", "WITHDRAWAL", "FEE", "EXPENSE", "TAX");

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
      return new CashFlowResponse(
          month.toString(), List.of(), List.of(), List.of(), List.of(), true);
    }
    LocalDate from = month.atDay(1);
    LocalDate to = month.atEndOfMonth();

    // Both queries sum signed, cash-direction amounts (money out is negative), so a spend is the
    // negated sum and a void's reversing row nets against its original.
    List<CurrencyAmount> income =
        asIs(transactionRepository.sumIncomeByCurrency(accountIds, INCOME_TYPES, from, to));
    List<CurrencyAmount> spending =
        asSpent(transactionRepository.sumSpendingByCurrency(accountIds, SPENDING_TYPES, from, to));
    List<CurrencyAmount> saving =
        net(
            transactionRepository.sumSavingMovementsByCurrency(accountIds, true, from, to),
            transactionRepository.sumSavingMovementsByCurrency(accountIds, false, from, to));
    List<CurrencyAmount> pending =
        asIs(transactionRepository.sumPendingReviewByCurrency(accountIds, from, to));
    return new CashFlowResponse(
        month.toString(), income, spending, saving, pending, pending.isEmpty());
  }

  private static List<CurrencyAmount> asIs(List<Object[]> rows) {
    return rows.stream()
        .map(row -> new CurrencyAmount((String) row[0], (BigDecimal) row[1]))
        .toList();
  }

  // Money moved into saving, less money moved back out, per currency; a currency that nets to
  // exactly zero is still shown, since money did move.
  private static List<CurrencyAmount> net(List<Object[]> into, List<Object[]> outOf) {
    Map<String, BigDecimal> byCurrency = new TreeMap<>();
    into.forEach(row -> byCurrency.merge((String) row[0], (BigDecimal) row[1], BigDecimal::add));
    outOf.forEach(
        row -> byCurrency.merge((String) row[0], ((BigDecimal) row[1]).negate(), BigDecimal::add));
    return byCurrency.entrySet().stream()
        .map(entry -> new CurrencyAmount(entry.getKey(), entry.getValue()))
        .toList();
  }

  private static List<CurrencyAmount> asSpent(List<Object[]> rows) {
    return rows.stream()
        .map(row -> new CurrencyAmount((String) row[0], ((BigDecimal) row[1]).negate()))
        .toList();
  }
}
