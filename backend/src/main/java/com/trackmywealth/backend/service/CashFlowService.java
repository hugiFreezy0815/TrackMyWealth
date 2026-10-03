package com.trackmywealth.backend.service;

import com.trackmywealth.backend.dto.AccessLevelValues;
import com.trackmywealth.backend.dto.CashFlowResponse;
import com.trackmywealth.backend.dto.CashFlowResponse.CurrencyAmount;
import com.trackmywealth.backend.dto.CurrencyConversionResult;
import com.trackmywealth.backend.entity.Account;
import com.trackmywealth.backend.repository.AccountRepository;
import com.trackmywealth.backend.repository.TransactionRepository;
import com.trackmywealth.backend.security.AuthenticatedUserPrincipal;
import com.trackmywealth.backend.validation.CurrencyCodes;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.stream.Stream;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * US-10-01 (replacing US-09-02's spending-only view): one month's cash flow, by booking date -
 * never a card's settlement date (FR-CC-009) - in four figures that never overlap
 * (FR-CF-001/002/003):
 *
 * <ul>
 *   <li><b>income</b> - {@code INCOME}, {@code INTEREST} and {@code DIVIDEND};
 *   <li><b>spending</b> - card purchases, withdrawals, expenses, fees and tax (consumption);
 *   <li><b>saving</b> - money moved from an account that does not count as saving into one that
 *       does ({@code counts_as_saving}, V41), less money moved back out; a transfer between two
 *       alike accounts is in no figure at all, and neither is a trade. The flag is read as it is
 *       now, not as it was when the money moved: changing an account's flag restates every month,
 *       like re-classifying the account. An endpoint that changes the flag must keep it so, or
 *       record the flag per transfer instead;
 *   <li><b>pendingReview</b> - what awaits a member's decision before it can count anywhere: an
 *       unresolved card settlement, a proposed transfer pair, an unlinked transfer leg.
 * </ul>
 *
 * <p>Left out entirely: any row flagged an internal transfer (a card settlement or own-account
 * transfer, DM-05) except as saving; a voided row with its reversal (US-07-02); a soft-deleted row.
 * {@code complete} is {@code false} while anything is pending review, or while a converted figure
 * is unknown. The savings rate is US-10-05.
 *
 * <p>US-06-05: without a {@code currency}, each figure is given per original transaction currency.
 * With one, each figure is a single amount in that currency, every booking date converted at its
 * own rate (FR-CUR-011's realised-flow rule) - the database groups rows by booking date and source
 * currency first, so there is one rate lookup per date and currency, not per ledger row. Stored
 * amounts and currencies are never rewritten. A converted figure combines up to a month of daily
 * rates, so it carries the carried-forward and stale marks (any day) but no single rate or date
 * (decision on #224).
 *
 * <p>Only accounts the caller may {@code READ} contribute: transaction-level detail is not shown at
 * {@code BALANCE_ONLY}.
 */
@Service
public class CashFlowService {

  // Money out of an ordinary account (WITHDRAWAL, EXPENSE, TAX) or off a card
  // (CREDIT_CARD_PURCHASE), plus FEE (US-09-04): a disclosed foreign-transaction fee is a real cost
  // the member incurred, not folded into the purchase amount - the story's own purpose ("see the
  // true cost including any FX fee") is exactly this: it must show up in spending on its own. A
  // card payment typed EXPENSE or WITHDRAWAL is kept out again by settlement matching
  // (SettlementDetectionService), never here. INCOME, DEPOSIT, INTEREST and REFUND are inflows and
  // deliberately not netted against spending.
  // US-10-01: what the household earns. A refund is not income (it undoes spending); a DEPOSIT is
  // money moved in, which is either a transfer or of unknown origin.
  private static final Set<String> INCOME_TYPES = Set.of("INCOME", "INTEREST", "DIVIDEND");

  private static final Set<String> SPENDING_TYPES =
      Set.of("CREDIT_CARD_PURCHASE", "WITHDRAWAL", "FEE", "EXPENSE", "TAX");

  private final AccessControlService accessControlService;
  private final AccountRepository accountRepository;
  private final TransactionRepository transactionRepository;
  private final FxRateService fxRateService;
  private final String fxDefaultSource;

  public CashFlowService(
      AccessControlService accessControlService,
      AccountRepository accountRepository,
      TransactionRepository transactionRepository,
      FxRateService fxRateService,
      @Value("${app.fx.default-source}") String fxDefaultSource) {
    this.accessControlService = accessControlService;
    this.accountRepository = accountRepository;
    this.transactionRepository = transactionRepository;
    this.fxRateService = fxRateService;
    this.fxDefaultSource = fxDefaultSource;
  }

  @Transactional(readOnly = true)
  public CashFlowResponse getCashFlow(
      YearMonth month, AuthenticatedUserPrincipal actor, String requestedCurrency) {
    // Who is asking before what they ask for, as in every other read taking a currency.
    UUID memberId = accessControlService.requireActingMember(actor);
    // null keeps every figure in its original transaction currencies.
    String targetCurrency = CurrencyCodes.requestedOrDefault(requestedCurrency, null);
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
    // One query per figure, grouped by booking date and source currency. All of them sum signed,
    // cash-direction amounts (money out is negative), so a spend is the negated sum, saving is
    // money in less money back out, and a void's reversing row nets against its original. The
    // original-currency view sums these rows per currency, the converted view converts each day
    // at its own rate; both read the same queries, so their rules (settlements, proposed matches,
    // transfers) cannot drift apart.
    List<Object[]> income =
        transactionRepository.sumIncomeByDateAndCurrency(accountIds, INCOME_TYPES, from, to);
    List<Object[]> spending =
        negated(
            transactionRepository.sumSpendingByDateAndCurrency(
                accountIds, SPENDING_TYPES, from, to));
    List<Object[]> saving =
        Stream.concat(
                transactionRepository
                    .sumSavingMovementsByDateAndCurrency(accountIds, true, from, to)
                    .stream(),
                negated(
                    transactionRepository.sumSavingMovementsByDateAndCurrency(
                        accountIds, false, from, to))
                    .stream())
            .toList();
    List<Object[]> pending =
        transactionRepository.sumPendingReviewByDateAndCurrency(accountIds, from, to);

    if (targetCurrency == null) {
      return new CashFlowResponse(
          month.toString(),
          perCurrency(income),
          perCurrency(spending),
          perCurrency(saving),
          perCurrency(pending),
          pending.isEmpty());
    }
    // Each booking date and source currency is converted once, at that day's rate.
    Map<LocalDate, Map<String, Optional<CurrencyConversionResult>>> rates = new HashMap<>();
    List<CurrencyAmount> convertedIncome = converted(income, targetCurrency, rates);
    List<CurrencyAmount> convertedSpending = converted(spending, targetCurrency, rates);
    List<CurrencyAmount> convertedSaving = converted(saving, targetCurrency, rates);
    List<CurrencyAmount> convertedPending = converted(pending, targetCurrency, rates);
    // PR-011: an unknown figure makes the month incomplete, as an unknown account value does for
    // net worth and the institution summary.
    boolean allKnown =
        Stream.of(convertedIncome, convertedSpending, convertedSaving, convertedPending)
            .flatMap(List::stream)
            .allMatch(CurrencyAmount::valueKnown);
    return new CashFlowResponse(
        month.toString(),
        convertedIncome,
        convertedSpending,
        convertedSaving,
        convertedPending,
        pending.isEmpty() && allKnown);
  }

  // (booking date, currency, amount) rows summed per currency, in currency order; a currency that
  // nets to exactly zero is still shown, since money did move.
  private static List<CurrencyAmount> perCurrency(List<Object[]> rows) {
    Map<String, BigDecimal> byCurrency = new TreeMap<>();
    rows.forEach(row -> byCurrency.merge((String) row[1], (BigDecimal) row[2], BigDecimal::add));
    return byCurrency.entrySet().stream()
        .map(entry -> new CurrencyAmount(entry.getKey(), entry.getValue()))
        .toList();
  }

  // One figure in targetCurrency: unknown (never zero) when any contributing day has no rate, and
  // carried-forward or stale when any contributing day's rate is (PR-011).
  private List<CurrencyAmount> converted(
      List<Object[]> rows,
      String targetCurrency,
      Map<LocalDate, Map<String, Optional<CurrencyConversionResult>>> rates) {
    if (rows.isEmpty()) {
      return List.of();
    }
    BigDecimal amount = BigDecimal.ZERO;
    boolean valueKnown = true;
    boolean carriedForward = false;
    boolean stale = false;
    for (Object[] row : rows) {
      LocalDate bookingDate = (LocalDate) row[0];
      Optional<CurrencyConversionResult> conversion =
          rates
              .computeIfAbsent(bookingDate, date -> new HashMap<>())
              .computeIfAbsent(
                  (String) row[1],
                  source ->
                      fxRateService.tryGetConversionRate(
                          source, targetCurrency, bookingDate, fxDefaultSource));
      if (conversion.isEmpty()) {
        valueKnown = false;
        continue;
      }
      CurrencyConversionResult rate = conversion.get();
      amount = amount.add(fxRateService.applyRate((BigDecimal) row[2], rate));
      carriedForward |= rate.carriedForward();
      stale |= rate.stale();
    }
    return List.of(
        new CurrencyAmount(
            targetCurrency, valueKnown ? amount : null, valueKnown, carriedForward, stale));
  }

  private static List<Object[]> negated(List<Object[]> rows) {
    return rows.stream()
        .map(row -> new Object[] {row[0], row[1], ((BigDecimal) row[2]).negate()})
        .toList();
  }
}
