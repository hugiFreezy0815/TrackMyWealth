package com.trackmywealth.backend.service;

import com.trackmywealth.backend.dto.AccountValuation;
import com.trackmywealth.backend.dto.DatedAmount;
import com.trackmywealth.backend.dto.DatedCashFlows;
import com.trackmywealth.backend.dto.PeriodReturn;
import com.trackmywealth.backend.dto.PeriodReturns;
import com.trackmywealth.backend.dto.PortfolioSection;
import com.trackmywealth.backend.dto.ReturnScopeContext;
import com.trackmywealth.backend.dto.ReturnScopePlan;
import com.trackmywealth.backend.dto.ReturnTerm;
import com.trackmywealth.backend.dto.SignedAmounts;
import com.trackmywealth.backend.dto.TrackingStartSources;
import com.trackmywealth.backend.entity.Account;
import com.trackmywealth.backend.repository.ValueHistoryStartRepository;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.BiPredicate;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * US-11-07 (#607): the period return of a history scope - net worth, a portfolio, or a section -
 * measured over each account's tracked history with Modified Dietz (ADR 0009, methodology "Period
 * return"):
 *
 * <pre>R = (V_end − V_start − ΣCF) / (V_start + Σ w_i · CF_i),  w_i = (T − t_i) / T</pre>
 *
 * <p>An account's <b>tracking start</b> is the first day its value rests on a recorded balance, by
 * the same capability flags as its value ({@code AccountValuationService}): its first manual
 * valuation, its first holdings statement, its first snapshot with a balance (an account without a
 * ledger, or a loan without an original principal), or its opening balance (an account with a
 * ledger). A card without an opening balance and a loan with an original principal have a value
 * from their first day, so they start at their inception (PO 2026-11-20). An ordinary ledger
 * without an opening balance has no known value and is never tracked. The opening date alone moves
 * nothing, and no balance is imputed.
 *
 * <p>The return starts at the period start, or at the scope's earliest tracked day when that is
 * later ({@code returnFrom}). Its external flows are an account entering the tracked scope (with
 * its first known value), a lifecycle or portfolio change in or out of it (with its value that
 * day), and the scope's dated cash flows: external income and spending plus internal-transfer legs,
 * so a transfer between two tracked accounts of the scope cancels out. The return measures the
 * tracked accounts only, so a transfer to or from an untracked account of the scope - a ledger
 * without an opening balance - is money leaving or entering what is measured, like one to another
 * scope: a flow, never a gain or loss. Interest, dividends, fees and tax on an account holding
 * positions are return, not flows (PO 2026-11-20). A booking counts from the day after the
 * account's tracking start, and only while the account was in the scope the day before: a balance
 * on a day already contains that day's bookings.
 *
 * <p>The return is unknown - never zero - when a tracked value or a flow is unknown (no FX rate, no
 * resolvable value, a row pending review), when no day has passed, or when the denominator is 0 or
 * less.
 */
@Service
public class PeriodReturnService {

  private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

  private final ValueHistoryStartRepository historyStartRepository;
  private final AccountValuationService accountValuationService;
  private final CashFlowService cashFlowService;

  public PeriodReturnService(
      ValueHistoryStartRepository historyStartRepository,
      AccountValuationService accountValuationService,
      CashFlowService cashFlowService) {
    this.historyStartRepository = historyStartRepository;
    this.accountValuationService = accountValuationService;
    this.cashFlowService = cashFlowService;
  }

  /**
   * The return of {@code accounts} from {@code from} to {@code to}, and of each section when {@code
   * sections} maps accounts to one. {@code inception} is each account's existing inception day,
   * {@code stateChangeDays} the business days its lifecycle or portfolio state changed, and {@code
   * included} whether it is in the scope on a day by its effective state then. The caller has
   * already authorized the accounts.
   */
  @Transactional(readOnly = true)
  public PeriodReturns returns(
      List<Account> accounts,
      LocalDate from,
      LocalDate to,
      String currency,
      Map<UUID, LocalDate> inception,
      Map<UUID, List<LocalDate>> stateChangeDays,
      BiPredicate<UUID, LocalDate> included,
      Map<UUID, PortfolioSection> sections) {
    Map<UUID, LocalDate> trackingStarts = new HashMap<>();
    Map<UUID, Account> tracked = tracked(accounts, to, inception, trackingStarts);
    ReturnScopeContext context =
        new ReturnScopeContext(from, to, trackingStarts, stateChangeDays, included);

    Set<PortfolioSection> sectionSet = EnumSet.noneOf(PortfolioSection.class);
    sectionSet.addAll(sections.values());
    List<PortfolioSection> present = List.copyOf(sectionSet);
    List<ReturnScopeContext> contexts = new ArrayList<>();
    List<ReturnScopePlan> plans = new ArrayList<>();
    for (PortfolioSection section : present) {
      Set<UUID> inSection = new HashSet<>();
      for (UUID id : tracked.keySet()) {
        if (section == sections.get(id)) {
          inSection.add(id);
        }
      }
      contexts.add(context);
      plans.add(plan(context, inSection));
    }
    contexts.add(context);
    plans.add(plan(context, tracked.keySet()));

    List<PeriodReturn> results = evaluate(contexts, plans, tracked, to, currency);
    Map<PortfolioSection, PeriodReturn> sectionReturns = new EnumMap<>(PortfolioSection.class);
    for (int i = 0; i < present.size(); i++) {
      sectionReturns.put(present.get(i), results.get(i));
    }
    return new PeriodReturns(results.get(present.size()), sectionReturns);
  }

  /**
   * #645: the return of many scopes at once - the Overview's portfolio rows - each in its own
   * currency, from {@code from} to {@code to}. {@code scopes} maps a scope's id to its currency and
   * {@code included} gives, per scope, whether an account is in it on a day by its effective state
   * then. Scopes that share a currency share one valuation pass and one cash-flow query, so the
   * work grows with the number of currencies, never with the number of scopes.
   */
  @Transactional(readOnly = true)
  public Map<UUID, PeriodReturn> scopeReturns(
      List<Account> accounts,
      LocalDate from,
      LocalDate to,
      Map<UUID, String> scopes,
      Map<UUID, LocalDate> inception,
      Map<UUID, List<LocalDate>> stateChangeDays,
      Map<UUID, BiPredicate<UUID, LocalDate>> included) {
    if (scopes.isEmpty()) {
      return Map.of();
    }
    Map<UUID, LocalDate> trackingStarts = new HashMap<>();
    Map<UUID, Account> tracked = tracked(accounts, to, inception, trackingStarts);
    Map<String, List<UUID>> byCurrency = new TreeMap<>();
    scopes.forEach(
        (scope, currency) ->
            byCurrency.computeIfAbsent(currency, unused -> new ArrayList<>()).add(scope));
    Map<UUID, PeriodReturn> result = new HashMap<>();
    byCurrency.forEach(
        (currency, ids) -> {
          List<ReturnScopeContext> contexts = new ArrayList<>(ids.size());
          List<ReturnScopePlan> plans = new ArrayList<>(ids.size());
          for (UUID scope : ids) {
            ReturnScopeContext context =
                new ReturnScopeContext(
                    from, to, trackingStarts, stateChangeDays, included.get(scope));
            contexts.add(context);
            plans.add(plan(context, tracked.keySet()));
          }
          List<PeriodReturn> returns = evaluate(contexts, plans, tracked, to, currency);
          for (int i = 0; i < ids.size(); i++) {
            result.put(ids.get(i), returns.get(i));
          }
        });
    return Map.copyOf(result);
  }

  // The accounts with a tracking start on or before the period end, their starts put in {@code
  // trackingStarts}; read in one query.
  private Map<UUID, Account> tracked(
      List<Account> accounts,
      LocalDate to,
      Map<UUID, LocalDate> inception,
      Map<UUID, LocalDate> trackingStarts) {
    Map<UUID, TrackingStartSources> sources =
        historyStartRepository.trackingStartSources(accounts.stream().map(Account::getId).toList());
    Map<UUID, Account> tracked = new HashMap<>();
    for (Account account : accounts) {
      LocalDate start =
          trackingStart(account, sources.get(account.getId()), inception.get(account.getId()));
      if (start != null && !start.isAfter(to)) {
        trackingStarts.put(account.getId(), start);
        tracked.put(account.getId(), account);
      }
    }
    return tracked;
  }

  // The plans' returns, in order, from one valuation pass and one cash-flow query in {@code
  // currency}.
  private List<PeriodReturn> evaluate(
      List<ReturnScopeContext> contexts,
      List<ReturnScopePlan> plans,
      Map<UUID, Account> tracked,
      LocalDate to,
      String currency) {
    Map<LocalDate, Map<UUID, AccountValuation>> values = value(plans, tracked, currency);
    LocalDate earliest =
        plans.stream()
            .map(ReturnScopePlan::start)
            .filter(Objects::nonNull)
            .min(LocalDate::compareTo)
            .orElse(null);
    DatedCashFlows cashFlows =
        earliest == null
            ? new DatedCashFlows(List.of(), List.of())
            : cashFlowService.datedFlows(
                List.copyOf(tracked.keySet()),
                tracked.values().stream()
                    .filter(account -> !account.isHoldsPositions())
                    .map(Account::getId)
                    .toList(),
                earliest.plusDays(1),
                to,
                currency);
    List<PeriodReturn> results = new ArrayList<>(plans.size());
    for (int i = 0; i < plans.size(); i++) {
      results.add(result(plans.get(i), contexts.get(i), values, cashFlows));
    }
    return results;
  }

  /**
   * Modified Dietz in percent, two decimals: {@code null} when no day has passed or the denominator
   * is 0 or less. Each flow is weighted by the share of the period still to run after its day.
   */
  static @Nullable BigDecimal modifiedDietz(
      BigDecimal start, BigDecimal end, List<DatedAmount> flows, LocalDate from, LocalDate to) {
    long days = ChronoUnit.DAYS.between(from, to);
    if (days <= 0) {
      return null;
    }
    BigDecimal period = BigDecimal.valueOf(days);
    BigDecimal netFlows = BigDecimal.ZERO;
    BigDecimal weighted = BigDecimal.ZERO;
    for (DatedAmount flow : flows) {
      BigDecimal remaining = BigDecimal.valueOf(ChronoUnit.DAYS.between(flow.date(), to));
      netFlows = netFlows.add(flow.amount());
      weighted =
          weighted.add(flow.amount().multiply(remaining).divide(period, MathContext.DECIMAL64));
    }
    BigDecimal denominator = start.add(weighted);
    if (denominator.signum() <= 0) {
      return null;
    }
    return end.subtract(start)
        .subtract(netFlows)
        .multiply(HUNDRED)
        .divide(denominator, MathContext.DECIMAL64)
        .setScale(2, RoundingMode.HALF_UP);
  }

  /** See the class comment: the first day the account's value rests on a recorded balance. */
  static @Nullable LocalDate trackingStart(
      Account account, @Nullable TrackingStartSources sources, @Nullable LocalDate inception) {
    if (sources == null) {
      return null;
    }
    if (account.isManualValuation()) {
      return sources.firstValuation();
    }
    if (account.isHasAmortisation()) {
      return sources.hasOriginalPrincipal()
          ? earlier(inception, sources.firstBalance())
          : sources.firstBalance();
    }
    if (account.isHoldsPositions()) {
      return sources.firstHoldings();
    }
    if (!account.isHasTransactions()) {
      return sources.firstBalance();
    }
    if (sources.openingBalance() != null) {
      return sources.openingBalance();
    }
    return account.isHasStatementCycle() ? inception : null;
  }

  private static @Nullable LocalDate earlier(@Nullable LocalDate a, @Nullable LocalDate b) {
    if (a == null) {
      return b;
    }
    return b == null || a.isBefore(b) ? a : b;
  }

  // The first day on or after the period start the account is both tracked and in the scope.
  private static @Nullable LocalDate entry(ReturnScopeContext context, UUID accountId) {
    LocalDate tracking = context.trackingStarts().get(accountId);
    LocalDate first = tracking.isAfter(context.from()) ? tracking : context.from();
    if (context.included().test(accountId, first)) {
      return first;
    }
    LocalDate entry = null;
    for (LocalDate day : context.stateChangeDays().getOrDefault(accountId, List.of())) {
      if (day.isAfter(first)
          && !day.isAfter(context.to())
          && (entry == null || day.isBefore(entry))
          && context.included().test(accountId, day)) {
        entry = day;
      }
    }
    return entry;
  }

  static ReturnScopePlan plan(ReturnScopeContext context, Set<UUID> accountIds) {
    LocalDate start = null;
    for (UUID id : accountIds) {
      LocalDate entry = entry(context, id);
      if (entry != null && (start == null || entry.isBefore(start))) {
        start = entry;
      }
    }
    if (start == null) {
      return ReturnScopePlan.EMPTY;
    }
    BiPredicate<UUID, LocalDate> included = context.included();
    LocalDate to = context.to();
    List<ReturnTerm> terms = new ArrayList<>();
    for (UUID id : accountIds) {
      LocalDate tracking = context.trackingStarts().get(id);
      if (!tracking.isAfter(start) && included.test(id, start)) {
        terms.add(new ReturnTerm(id, start, ReturnTerm.Kind.START));
      }
      if (included.test(id, to)) {
        terms.add(new ReturnTerm(id, to, ReturnTerm.Kind.END));
      }
      if (tracking.isAfter(start) && included.test(id, tracking)) {
        terms.add(new ReturnTerm(id, tracking, ReturnTerm.Kind.FLOW_IN));
      }
      for (LocalDate day : context.stateChangeDays().getOrDefault(id, List.of())) {
        if (!day.isAfter(start) || day.isAfter(to) || !day.isAfter(tracking)) {
          continue;
        }
        boolean before = included.test(id, day.minusDays(1));
        boolean after = included.test(id, day);
        if (before != after) {
          terms.add(
              new ReturnTerm(id, day, after ? ReturnTerm.Kind.FLOW_IN : ReturnTerm.Kind.FLOW_OUT));
        }
      }
    }
    return new ReturnScopePlan(start, terms, accountIds);
  }

  // Every value the plans read. Accounts on an opening balance and its ledger - the usual case,
  // and each tracking start is a day of its own - are valued for all days in one bulk pass, as for
  // the chart; only the others take one batch valuation per day they are read on.
  private Map<LocalDate, Map<UUID, AccountValuation>> value(
      List<ReturnScopePlan> plans, Map<UUID, Account> accounts, String currency) {
    Map<LocalDate, Map<UUID, Account>> wanted = new TreeMap<>();
    Map<UUID, Account> read = new HashMap<>();
    for (ReturnScopePlan plan : plans) {
      for (ReturnTerm term : plan.terms()) {
        Account account = accounts.get(term.accountId());
        wanted
            .computeIfAbsent(term.date(), unused -> new HashMap<>())
            .put(account.getId(), account);
        read.put(account.getId(), account);
      }
    }
    Map<LocalDate, Map<UUID, AccountValuation>> bulk =
        accountValuationService.historyFromOpeningBalances(
            read.values(), currency, List.copyOf(wanted.keySet()));
    Map<LocalDate, Map<UUID, AccountValuation>> values = new HashMap<>();
    wanted.forEach(
        (date, byId) -> {
          Map<UUID, AccountValuation> onDate = new HashMap<>();
          List<Account> others = new ArrayList<>();
          Map<UUID, AccountValuation> fromBulk = bulk.getOrDefault(date, Map.of());
          for (Account account : byId.values()) {
            AccountValuation valuation = fromBulk.get(account.getId());
            if (valuation == null) {
              others.add(account);
            } else {
              onDate.put(account.getId(), valuation);
            }
          }
          if (!others.isEmpty()) {
            for (AccountValuation valuation :
                accountValuationService.valueAll(others, currency, date)) {
              onDate.put(valuation.accountId(), valuation);
            }
          }
          values.put(date, onDate);
        });
    return values;
  }

  static PeriodReturn result(
      ReturnScopePlan plan,
      ReturnScopeContext context,
      Map<LocalDate, Map<UUID, AccountValuation>> values,
      DatedCashFlows cashFlows) {
    LocalDate start = plan.start();
    if (start == null) {
      return PeriodReturn.NONE;
    }
    PeriodReturn unknown = new PeriodReturn(null, start);
    BigDecimal startValue = BigDecimal.ZERO;
    BigDecimal endValue = BigDecimal.ZERO;
    List<DatedAmount> flows = new ArrayList<>();
    for (ReturnTerm term : plan.terms()) {
      AccountValuation valuation = values.getOrDefault(term.date(), Map.of()).get(term.accountId());
      if (valuation == null || !valuation.valueKnown()) {
        return unknown;
      }
      BigDecimal value = SignedAmounts.of(valuation);
      // No switch on the enum: javac would add a synthetic PeriodReturnService$1 to the service
      // package, which ArchitectureTest's naming rule rejects.
      ReturnTerm.Kind kind = term.kind();
      if (kind == ReturnTerm.Kind.START) {
        startValue = startValue.add(value);
      } else if (kind == ReturnTerm.Kind.END) {
        endValue = endValue.add(value);
      } else {
        flows.add(
            new DatedAmount(term.date(), kind == ReturnTerm.Kind.FLOW_IN ? value : value.negate()));
      }
    }
    for (DatedCashFlows.Flow pending : cashFlows.pending()) {
      if (counts(plan, context, pending)) {
        return unknown;
      }
    }
    for (DatedCashFlows.Flow flow : cashFlows.flows()) {
      if (!counts(plan, context, flow)) {
        continue;
      }
      BigDecimal amount = flow.amount();
      if (amount == null) {
        return unknown;
      }
      flows.add(new DatedAmount(flow.date(), amount));
    }
    return new PeriodReturn(modifiedDietz(startValue, endValue, flows, start, context.to()), start);
  }

  // A booking is a flow of the scope from the day after the account's tracking start, while the
  // account was in the scope the day before: a value on a day already holds that day's bookings.
  private static boolean counts(
      ReturnScopePlan plan, ReturnScopeContext context, DatedCashFlows.Flow flow) {
    UUID id = flow.accountId();
    LocalDate start = Objects.requireNonNull(plan.start());
    LocalDate tracking = context.trackingStarts().get(id);
    LocalDate day = flow.date();
    return plan.members().contains(id)
        && tracking != null
        && day.isAfter(start)
        && !day.isAfter(context.to())
        && day.isAfter(tracking)
        && context.included().test(id, day.minusDays(1));
  }
}
