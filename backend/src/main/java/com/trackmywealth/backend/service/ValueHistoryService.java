package com.trackmywealth.backend.service;

import com.trackmywealth.backend.dto.AccountHistoryState;
import com.trackmywealth.backend.dto.AccountValuation;
import com.trackmywealth.backend.dto.CashFlowContribution;
import com.trackmywealth.backend.dto.PortfolioCapitalFlows;
import com.trackmywealth.backend.dto.PortfolioPeriodChange;
import com.trackmywealth.backend.dto.PortfolioPlacement;
import com.trackmywealth.backend.dto.PortfolioSection;
import com.trackmywealth.backend.dto.SignedAmounts;
import com.trackmywealth.backend.dto.ValueBasisValues;
import com.trackmywealth.backend.dto.ValueHistoryPeriod;
import com.trackmywealth.backend.dto.ValueHistoryResponse;
import com.trackmywealth.backend.dto.ValueHistoryResponse.Change;
import com.trackmywealth.backend.dto.ValueHistoryResponse.Point;
import com.trackmywealth.backend.dto.ValueHistoryResponse.SectionChange;
import com.trackmywealth.backend.dto.ValueHistorySnapshot;
import com.trackmywealth.backend.entity.Account;
import com.trackmywealth.backend.entity.Portfolio;
import com.trackmywealth.backend.entity.Workspace;
import com.trackmywealth.backend.repository.AccountHistoryStateRepository;
import com.trackmywealth.backend.repository.AccountRepository;
import com.trackmywealth.backend.repository.ValueHistoryStartRepository;
import com.trackmywealth.backend.repository.WorkspaceRepository;
import com.trackmywealth.backend.security.AuthenticatedUserPrincipal;
import com.trackmywealth.backend.validation.CurrencyCodes;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * US-11-06: historical valuation calculated entirely by the server. Uses the same dated account
 * valuation as the current balance, net worth and Portfolio View, including native-currency
 * conversion on each point's date. Missing values mark the entire history incomplete, never
 * disappear without an account-specific warning.
 */
@Service
public class ValueHistoryService {

  private static final String ACTIVE_STATUS = "ACTIVE";

  private final AccessControlService accessControlService;
  private final PortfolioLookupService portfolioLookupService;
  private final WorkspaceRepository workspaceRepository;
  private final AccountRepository accountRepository;
  private final AccountHistoryStateRepository accountHistoryStateRepository;
  private final AccountValuationService accountValuationService;
  private final AccountService accountService;
  private final BusinessDateService businessDateService;
  private final CashFlowService cashFlowService;
  private final ValueHistoryStartRepository historyStartRepository;

  public ValueHistoryService(
      AccessControlService accessControlService,
      PortfolioLookupService portfolioLookupService,
      WorkspaceRepository workspaceRepository,
      AccountRepository accountRepository,
      AccountHistoryStateRepository accountHistoryStateRepository,
      AccountValuationService accountValuationService,
      AccountService accountService,
      BusinessDateService businessDateService,
      CashFlowService cashFlowService,
      ValueHistoryStartRepository historyStartRepository) {
    this.accessControlService = accessControlService;
    this.portfolioLookupService = portfolioLookupService;
    this.workspaceRepository = workspaceRepository;
    this.accountRepository = accountRepository;
    this.accountHistoryStateRepository = accountHistoryStateRepository;
    this.accountValuationService = accountValuationService;
    this.accountService = accountService;
    this.businessDateService = businessDateService;
    this.cashFlowService = cashFlowService;
    this.historyStartRepository = historyStartRepository;
  }

  @Transactional(readOnly = true)
  public ValueHistoryResponse netWorthHistory(
      String periodParam, String requestedCurrency, AuthenticatedUserPrincipal actor) {
    UUID memberId = accessControlService.requireActingMember(actor);
    Workspace workspace =
        workspaceRepository
            .findById(actor.workspaceId())
            .orElseThrow(
                () -> accessControlService.denyAsNotFound(actor, "Workspace", actor.workspaceId()));
    String currency = CurrencyCodes.requestedOrDefault(requestedCurrency, workspace.getCurrency());
    List<Account> accounts =
        accessControlService.accountsWithAccess(
            memberId, accountRepository.findByWorkspaceId(actor.workspaceId()));
    return history(periodParam, currency, accounts, actor, true, null);
  }

  @Transactional(readOnly = true)
  public ValueHistoryResponse portfolioHistory(
      UUID portfolioId, String periodParam, AuthenticatedUserPrincipal actor) {
    Portfolio portfolio = portfolioLookupService.findPortfolioOrThrow(portfolioId, actor);
    accessControlService.requirePortfolioAccess(actor, portfolio);
    // Historical membership is not the current portfolio assignment.
    List<Account> accounts =
        accessControlService.accountsWithAccess(
            accessControlService.requireActingMember(actor),
            accountRepository.findByWorkspaceId(actor.workspaceId()));
    return history(
        periodParam, portfolio.getPortfolioCurrency(), accounts, actor, false, portfolioId);
  }

  private ValueHistoryResponse history(
      String periodParam,
      String currency,
      List<Account> accounts,
      AuthenticatedUserPrincipal actor,
      boolean withSections,
      @Nullable UUID portfolioId) {
    ValueHistoryPeriod period = ValueHistoryPeriod.parse(periodParam);
    LocalDate today = businessDateService.today();
    List<UUID> allIds = accounts.stream().map(Account::getId).toList();
    Map<UUID, LocalDate> firstEvidence = historyStartRepository.firstEvidence(allIds);
    Map<UUID, List<AccountHistoryState>> historyStates =
        accountHistoryStateRepository.historyFor(allIds);
    ZoneId zone = businessDateService.zone();
    // A portfolio's MAX window must follow dated membership, not the accounts' current
    // portfolio ids. The workspace-wide earliest activity remains unchanged.
    LocalDate firstActivity =
        portfolioId == null
            ? historyStartRepository.firstActivity(actor.workspaceId(), null, today)
            : firstPortfolioActivity(
                accounts, portfolioId, firstEvidence, historyStates, zone, today);
    LocalDate from = period.start(today, firstActivity);
    List<LocalDate> dates = period.dates(from, today);
    Map<UUID, PortfolioPlacement> placements =
        withSections ? accountService.portfolioPlacementsOf(accounts) : Map.of();
    Map<LocalDate, Map<UUID, AccountValuation>> openingHistory =
        accountValuationService.historyFromOpeningBalances(accounts, currency, dates);
    List<Point> points = new ArrayList<>(dates.size());
    Set<String> warnings = new TreeSet<>();
    ValueHistorySnapshot first = null;
    ValueHistorySnapshot last = null;
    boolean complete = true;
    // Without a declared opening date, activity is evidence of existence but not proof
    // that values before the first observation were zero. Preserve the chart's missing-data
    // signal when the selected period extends into that uncertain interval.
    for (Account account : accounts) {
      if (account.getOpenedAt() == null) {
        LocalDate evidence = firstEvidence.get(account.getId());
        if (evidence != null
            && from.isBefore(evidence)
            && (portfolioId == null
                || affectsPortfolioBeforeEvidence(
                    account, portfolioId, from, evidence, historyStates, zone))) {
          warnings.add(account.getName() + ": inception before first activity is uncertain");
          complete = false;
        }
      }
    }
    for (LocalDate date : dates) {
      List<Account> eligible =
          onDate(
              accounts,
              date,
              businessDateService.zone(),
              firstEvidence,
              historyStates,
              portfolioId,
              warnings);
      ValueHistorySnapshot snapshot =
          snapshot(
              eligible,
              currency,
              date,
              placements,
              warnings,
              openingHistory.getOrDefault(date, Map.of()));
      points.add(new Point(date, snapshot.value(), snapshot.estimated()));
      complete &= snapshot.complete();
      if (first == null) {
        first = snapshot;
      }
      last = snapshot;
    }
    if (warnings.stream()
        .anyMatch(
            w ->
                w.contains("historical lifecycle state unavailable")
                    || w.contains("may have been restated"))) {
      complete = false;
    }
    ValueHistorySnapshot start = Objects.requireNonNull(first);
    ValueHistorySnapshot end = Objects.requireNonNull(last);
    List<UUID> accountIds = accounts.stream().map(Account::getId).toList();
    CashFlowContribution membership =
        membershipMovements(
            accounts, from, today, currency, portfolioId, firstEvidence, historyStates);
    complete &= membership.complete();
    warnings.addAll(membership.warnings());
    Change change;
    if (portfolioId == null) {
      CashFlowContribution contributions =
          cashFlowService.netContributions(accountIds, from.plusDays(1), today, currency);
      complete &= contributions.complete();
      warnings.addAll(contributions.warnings());
      change =
          change(
              start.value(),
              end.value(),
              contributions.complete() ? contributions.amount() : null,
              membership.complete() ? membership.amount() : null);
    } else {
      Map<UUID, Account> accountsById = new HashMap<>();
      for (Account account : accounts) {
        accountsById.put(account.getId(), account);
      }
      PortfolioCapitalFlows flows =
          cashFlowService.portfolioCapitalFlows(
              accountIds,
              from.plusDays(1),
              today,
              currency,
              (accountId, date) -> {
                Account account = accountsById.get(accountId);
                return account != null
                    && portfolioId.equals(
                        portfolioOfOnDate(
                            account, date, businessDateService.zone(), historyStates));
              });
      complete &= flows.complete();
      warnings.addAll(flows.warnings());
      BigDecimal memberAmount = membership.amount();
      BigDecimal internalAmount = flows.capitalMovements();
      BigDecimal capitalMovements =
          flows.complete()
                  && membership.complete()
                  && memberAmount != null
                  && internalAmount != null
              ? internalAmount.add(memberAmount)
              : null;
      if (capitalMovements == null) {
        complete = false;
        warnings.add("Portfolio capital movement attribution unavailable.");
      }
      change =
          change(
              start.value(),
              end.value(),
              flows.complete() ? flows.contributions() : null,
              capitalMovements);
    }
    List<SectionChange> sections = new ArrayList<>();
    if (withSections) {
      for (PortfolioSection section : PortfolioSection.values()) {
        if (start.sections().containsKey(section) || end.sections().containsKey(section)) {
          sections.add(
              new SectionChange(
                  section,
                  change(
                      start.sections().getOrDefault(section, BigDecimal.ZERO),
                      end.sections().getOrDefault(section, BigDecimal.ZERO),
                      null)));
        }
      }
    }
    return new ValueHistoryResponse(
        currency,
        period.wireValue(),
        from,
        today,
        points,
        change,
        sections,
        complete,
        List.copyOf(warnings));
  }

  /**
   * A portfolio row needs only its two period endpoints, never a 30-point chart. Calculate all
   * portfolios' endpoints in two batch valuation passes rather than 2 * portfolio count passes.
   */
  @Transactional(readOnly = true)
  public Map<UUID, PortfolioPeriodChange> monthlyChanges(
      Collection<Portfolio> portfolios, List<Account> accounts, LocalDate today) {
    if (portfolios.isEmpty()) {
      return Map.of();
    }
    LocalDate from = today.minusMonths(1);
    Set<String> currencies = new TreeSet<>();
    portfolios.forEach(portfolio -> currencies.add(portfolio.getPortfolioCurrency()));
    List<UUID> ids = accounts.stream().map(Account::getId).toList();
    Map<UUID, LocalDate> firstEvidence = historyStartRepository.firstEvidence(ids);
    Map<UUID, List<AccountHistoryState>> historyStates =
        accountHistoryStateRepository.historyFor(ids);
    List<Account> eligibleAtStart =
        onDate(
            accounts, from, businessDateService.zone(), firstEvidence, historyStates, null, null);
    List<Account> eligibleAtEnd =
        onDate(
            accounts, today, businessDateService.zone(), firstEvidence, historyStates, null, null);
    Map<UUID, Set<UUID>> idsAtStart = new HashMap<>();
    Map<UUID, Set<UUID>> idsAtEnd = new HashMap<>();
    for (Account account : eligibleAtStart) {
      AccountHistoryState state = stateOn(account, from, businessDateService.zone(), historyStates);
      UUID previousPortfolio = state == null ? account.getPortfolio().getId() : state.portfolioId();
      idsAtStart
          .computeIfAbsent(previousPortfolio, unused -> new java.util.HashSet<>())
          .add(account.getId());
    }
    for (Account account : eligibleAtEnd) {
      AccountHistoryState state =
          stateOn(account, today, businessDateService.zone(), historyStates);
      UUID currentPortfolio = state == null ? account.getPortfolio().getId() : state.portfolioId();
      idsAtEnd
          .computeIfAbsent(currentPortfolio, unused -> new java.util.HashSet<>())
          .add(account.getId());
    }
    var start = accountValuationService.valueAllInCurrencies(eligibleAtStart, currencies, from);
    var end = accountValuationService.valueAllInCurrencies(eligibleAtEnd, currencies, today);
    Map<UUID, PortfolioPeriodChange> result = new HashMap<>();
    for (Portfolio portfolio : portfolios) {
      UUID portfolioId = portfolio.getId();
      String currency = portfolio.getPortfolioCurrency();
      Set<UUID> startIds = idsAtStart.getOrDefault(portfolioId, Set.of());
      Set<UUID> endIds = idsAtEnd.getOrDefault(portfolioId, Set.of());
      List<AccountValuation> atStart =
          start.in(currency).stream().filter(v -> startIds.contains(v.accountId())).toList();
      List<AccountValuation> atEnd =
          end.in(currency).stream().filter(v -> endIds.contains(v.accountId())).toList();
      if (atStart.stream().anyMatch(v -> !v.valueKnown())
          || atEnd.stream().anyMatch(v -> !v.valueKnown())) {
        result.put(portfolioId, new PortfolioPeriodChange("1M", null, null));
        continue;
      }
      Change change = change(sum(atStart), sum(atEnd), null);
      result.put(portfolioId, new PortfolioPeriodChange("1M", change.amount(), change.percent()));
    }
    return Map.copyOf(result);
  }

  private ValueHistorySnapshot snapshot(
      List<Account> accounts,
      String currency,
      LocalDate asOf,
      Map<UUID, PortfolioPlacement> placements,
      Set<String> warnings,
      Map<UUID, AccountValuation> openingValues) {
    List<AccountValuation> valuations = new ArrayList<>();
    List<Account> otherAccounts = new ArrayList<>();
    for (Account account : accounts) {
      AccountValuation openingValue = openingValues.get(account.getId());
      if (openingValue == null) {
        otherAccounts.add(account);
      } else {
        valuations.add(openingValue);
      }
    }
    if (!otherAccounts.isEmpty()) {
      valuations.addAll(accountValuationService.valueAll(otherAccounts, currency, asOf));
    }
    boolean complete = true;
    boolean estimated = false;
    Map<PortfolioSection, BigDecimal> sections = new EnumMap<>(PortfolioSection.class);
    for (AccountValuation valuation : valuations) {
      warnings.addAll(valuation.warnings());
      if (!valuation.valueKnown()) {
        complete = false;
        warnings.add(valuation.name() + ": value unknown");
        continue;
      }
      String basis = valuation.valueBasis();
      LocalDate sourceDate = valuation.valueSourceDate();
      estimated |=
          sourceDate != null
              && sourceDate.isBefore(asOf)
              && (ValueBasisValues.LATEST_SNAPSHOT.equals(basis)
                  || ValueBasisValues.MANUAL_VALUATION.equals(basis)
                  || ValueBasisValues.LEDGER_FROM_STATEMENT_BALANCE.equals(basis));
      PortfolioPlacement placement = placements.get(valuation.accountId());
      if (placement != null) {
        sections.merge(placement.section(), SignedAmounts.of(valuation), BigDecimal::add);
      }
    }
    return new ValueHistorySnapshot(sum(valuations), complete, estimated, Map.copyOf(sections));
  }

  /**
   * An account changing portfolio or entering/leaving the tracked balance is a capital movement,
   * not a market gain/loss. Read its value at the effective transition date, rather than treating
   * the abrupt change in the chart as performance.
   */
  private CashFlowContribution membershipMovements(
      List<Account> accounts,
      LocalDate from,
      LocalDate to,
      String currency,
      @Nullable UUID portfolioId,
      Map<UUID, LocalDate> firstEvidence,
      Map<UUID, List<AccountHistoryState>> historyStates) {
    BigDecimal total = BigDecimal.ZERO;
    Set<String> warnings = new TreeSet<>();
    boolean complete = true;
    ZoneId zone = businessDateService.zone();
    for (Account account : accounts) {
      List<AccountHistoryState> states = historyStates.getOrDefault(account.getId(), List.of());
      LocalDate inception = inceptionDate(account, zone, firstEvidence.get(account.getId()));
      if (inception != null && inception.isAfter(from) && !inception.isAfter(to)) {
        AccountHistoryState atInception = stateOn(account, inception, zone, historyStates);
        boolean included =
            atInception == null
                ? ACTIVE_STATUS.equals(account.getStatus())
                    && (portfolioId == null || portfolioId.equals(account.getPortfolio().getId()))
                : ACTIVE_STATUS.equals(atInception.status())
                    && (portfolioId == null || portfolioId.equals(atInception.portfolioId()));
        if (included) {
          AccountValuation initial = accountValuationService.valueIn(account, currency, inception);
          warnings.addAll(initial.warnings());
          if (initial.valueKnown()) {
            total = total.add(SignedAmounts.of(initial));
          } else {
            complete = false;
            warnings.add(account.getName() + ": opening value unavailable");
          }
        }
      }
      for (int i = 1; i < states.size(); i++) {
        AccountHistoryState before = states.get(i - 1);
        AccountHistoryState after = states.get(i);
        LocalDate day = after.effectiveAt().atZoneSameInstant(zone).toLocalDate();
        if (!day.isAfter(from) || day.isAfter(to)) {
          continue;
        }
        boolean beforeIncluded =
            ACTIVE_STATUS.equals(before.status())
                && (portfolioId == null || portfolioId.equals(before.portfolioId()));
        boolean afterIncluded =
            ACTIVE_STATUS.equals(after.status())
                && (portfolioId == null || portfolioId.equals(after.portfolioId()));
        if (beforeIncluded == afterIncluded) {
          continue;
        }
        AccountValuation value = accountValuationService.valueIn(account, currency, day);
        warnings.addAll(value.warnings());
        if (!value.valueKnown()) {
          complete = false;
          warnings.add(account.getName() + ": value unknown at portfolio/lifecycle change");
          continue;
        }
        BigDecimal signed = SignedAmounts.of(value);
        total = total.add(afterIncluded ? signed : signed.negate());
      }
    }
    return new CashFlowContribution(complete ? total : null, complete, List.copyOf(warnings));
  }

  /**
   * Financial inception may predate the account row when snapshots, transactions, or manual
   * valuations were entered with their historical dates.
   */
  private static @Nullable LocalDate inceptionDate(
      Account account, ZoneId zone, @Nullable LocalDate evidence) {
    LocalDate created =
        account.getCreatedAt() == null
            ? null
            : account.getCreatedAt().atZoneSameInstant(zone).toLocalDate();
    LocalDate inception = account.getOpenedAt() != null ? account.getOpenedAt() : created;
    return evidence != null && (inception == null || evidence.isBefore(inception))
        ? evidence
        : inception;
  }

  /**
   * The first date this portfolio could have included an account. Account activity in another
   * portfolio before a later reassignment must never pull this portfolio's MAX window backward.
   * For migrated pre-tracking intervals, the baseline is our best available attribution and the
   * normal history warning exposes any uncertainty.
   */
  private static LocalDate firstPortfolioActivity(
      Collection<Account> accounts,
      UUID portfolioId,
      Map<UUID, LocalDate> firstEvidence,
      Map<UUID, List<AccountHistoryState>> historyStates,
      ZoneId zone,
      LocalDate today) {
    LocalDate earliest = today;
    for (Account account : accounts) {
      LocalDate inception = inceptionDate(account, zone, firstEvidence.get(account.getId()));
      if (inception == null || inception.isAfter(today)) {
        continue;
      }
      AccountHistoryState atInception = stateOn(account, inception, zone, historyStates);
      boolean includedAtInception =
          atInception == null
              ? ACTIVE_STATUS.equals(account.getStatus())
                  && portfolioId.equals(account.getPortfolio().getId())
              : ACTIVE_STATUS.equals(atInception.status())
                  && portfolioId.equals(atInception.portfolioId());
      if (includedAtInception && inception.isBefore(earliest)) {
        earliest = inception;
      }
      for (AccountHistoryState event : historyStates.getOrDefault(account.getId(), List.of())) {
        LocalDate date = event.effectiveAt().atZoneSameInstant(zone).toLocalDate();
        if (!date.isBefore(inception)
            && date.isBefore(earliest)
            && ACTIVE_STATUS.equals(event.status())
            && portfolioId.equals(event.portfolioId())) {
          earliest = date;
        }
      }
    }
    return earliest;
  }

  /**
   * An account from another portfolio cannot make a portfolio's entire history incomplete.
   * Consider only membership while the possibly-unobserved inception interval overlaps the
   * requested window, including an entry into the portfolio during that interval.
   */
  private static boolean affectsPortfolioBeforeEvidence(
      Account account,
      UUID portfolioId,
      LocalDate from,
      LocalDate evidence,
      Map<UUID, List<AccountHistoryState>> historyStates,
      ZoneId zone) {
    AccountHistoryState atStart = stateOn(account, from, zone, historyStates);
    if (atStart == null
        ? ACTIVE_STATUS.equals(account.getStatus())
            && portfolioId.equals(account.getPortfolio().getId())
        : ACTIVE_STATUS.equals(atStart.status())
            && portfolioId.equals(atStart.portfolioId())) {
      return true;
    }
    for (AccountHistoryState state : historyStates.getOrDefault(account.getId(), List.of())) {
      LocalDate date = state.effectiveAt().atZoneSameInstant(zone).toLocalDate();
      if (!date.isBefore(from)
          && date.isBefore(evidence)
          && ACTIVE_STATUS.equals(state.status())
          && portfolioId.equals(state.portfolioId())) {
        return true;
      }
    }
    return false;
  }

  private static List<Account> onDate(
      Collection<Account> accounts,
      LocalDate date,
      ZoneId businessZone,
      Map<UUID, LocalDate> firstEvidence,
      Map<UUID, List<AccountHistoryState>> historyStates,
      @Nullable UUID portfolioId,
      @Nullable Set<String> warnings) {
    return accounts.stream()
        .filter(
            account -> {
              LocalDate inception =
                  inceptionDate(account, businessZone, firstEvidence.get(account.getId()));
              if (inception == null || date.isBefore(inception)) {
                return false;
              }
              AccountHistoryState state = stateOn(account, date, businessZone, historyStates);
              if (state == null) {
                // For pre-migration data without an event, preserve the current state but
                // do not pretend this reconstructed history is complete.
                if (warnings != null) {
                  warnings.add(account.getName() + ": historical lifecycle state unavailable");
                }
                return ACTIVE_STATUS.equals(account.getStatus())
                    && (portfolioId == null || portfolioId.equals(account.getPortfolio().getId()));
              }
              // A migration baseline is authoritative from its effective date onward;
              // only its pre-baseline reconstruction is unverified.
              if (!state.verified()
                  && date.isBefore(state.effectiveAt().atZoneSameInstant(businessZone).toLocalDate())
                  && warnings != null) {
                warnings.add(
                    account.getName()
                        + ": portfolio and archive history before tracking"
                        + " may have been restated");
              }
              return ACTIVE_STATUS.equals(state.status())
                  && (portfolioId == null || portfolioId.equals(state.portfolioId()));
            })
        .toList();
  }

  private static @Nullable UUID portfolioOfOnDate(
      Account account,
      LocalDate date,
      ZoneId zone,
      Map<UUID, List<AccountHistoryState>> historyStates) {
    AccountHistoryState state = stateOn(account, date, zone, historyStates);
    return state == null ? account.getPortfolio().getId() : state.portfolioId();
  }

  private static @Nullable AccountHistoryState stateOn(
      Account account,
      LocalDate date,
      ZoneId zone,
      Map<UUID, List<AccountHistoryState>> historyStates) {
    List<AccountHistoryState> events = historyStates.getOrDefault(account.getId(), List.of());
    if (events.isEmpty()) {
      return null;
    }
    AccountHistoryState asOf = events.get(0);
    for (AccountHistoryState event : events) {
      if (event.effectiveAt().atZoneSameInstant(zone).toLocalDate().isAfter(date)) {
        break;
      }
      asOf = event;
    }
    return asOf;
  }

  private static BigDecimal sum(Collection<AccountValuation> valuations) {
    BigDecimal sum = BigDecimal.ZERO;
    for (AccountValuation valuation : valuations) {
      if (valuation.valueKnown()) {
        sum = sum.add(SignedAmounts.of(valuation));
      }
    }
    return sum;
  }

  static Change change(BigDecimal from, BigDecimal to, BigDecimal contributions) {
    return change(from, to, contributions, BigDecimal.ZERO);
  }

  static Change change(
      BigDecimal from,
      BigDecimal to,
      @Nullable BigDecimal contributions,
      @Nullable BigDecimal capitalMovements) {
    BigDecimal amount = to.subtract(from);
    BigDecimal percent =
        from.signum() == 0
            ? null
            : amount.multiply(BigDecimal.valueOf(100)).divide(from.abs(), 2, RoundingMode.HALF_UP);
    return new Change(
        amount,
        percent,
        contributions,
        contributions == null || capitalMovements == null
            ? null
            : amount.subtract(contributions).subtract(capitalMovements),
        capitalMovements);
  }
}
