package com.trackmywealth.backend.service;

import com.trackmywealth.backend.dto.AccountHistoryState;
import com.trackmywealth.backend.dto.AccountValuation;
import com.trackmywealth.backend.dto.CashFlowContribution;
import com.trackmywealth.backend.dto.PortfolioPeriodChange;
import com.trackmywealth.backend.dto.PortfolioPlacement;
import com.trackmywealth.backend.dto.PortfolioSection;
import com.trackmywealth.backend.dto.SignedAmounts;
import com.trackmywealth.backend.dto.ValueBasisValues;
import com.trackmywealth.backend.dto.ValueHistoryPeriod;
import com.trackmywealth.backend.dto.ValueHistorySnapshot;
import com.trackmywealth.backend.dto.ValueHistoryResponse;
import com.trackmywealth.backend.dto.ValueHistoryResponse.Change;
import com.trackmywealth.backend.dto.ValueHistoryResponse.Point;
import com.trackmywealth.backend.dto.ValueHistoryResponse.SectionChange;
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
    return history(periodParam, portfolio.getPortfolioCurrency(), accounts, actor, false, portfolioId);
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
    LocalDate from =
        period.start(today, historyStartRepository.firstActivity(actor.workspaceId(), portfolioId, today));
    List<LocalDate> dates = period.dates(from, today);
    List<UUID> allIds = accounts.stream().map(Account::getId).toList();
    Map<UUID, LocalDate> firstEvidence = historyStartRepository.firstEvidence(allIds);
    Map<UUID, List<AccountHistoryState>> historyStates =
        accountHistoryStateRepository.historyFor(allIds);
    Map<UUID, PortfolioPlacement> placements =
        withSections ? accountService.portfolioPlacementsOf(accounts) : Map.of();
    List<Point> points = new ArrayList<>(dates.size());
    Set<String> warnings = new TreeSet<>();
    ValueHistorySnapshot first = null;
    ValueHistorySnapshot last = null;
    boolean complete = true;
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
      ValueHistorySnapshot snapshot = snapshot(eligible, currency, date, placements, warnings);
      points.add(new Point(date, snapshot.value(), snapshot.estimated()));
      complete &= snapshot.complete();
      if (first == null) {
        first = snapshot;
      }
      last = snapshot;
    }
    if (warnings.stream().anyMatch(w -> w.contains("historical lifecycle state unavailable")
        || w.contains("may have been restated"))) {
      complete = false;
    }
    ValueHistorySnapshot start = Objects.requireNonNull(first);
    ValueHistorySnapshot end = Objects.requireNonNull(last);
    List<UUID> accountIds = accounts.stream().map(Account::getId).toList();
    CashFlowContribution contributions =
        cashFlowService.netContributions(accountIds, from.plusDays(1), today, currency);
    complete &= contributions.complete();
    warnings.addAll(contributions.warnings());
    Change change =
        change(start.value(), end.value(), contributions.complete() ? contributions.amount() : null);
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
      idsAtStart.computeIfAbsent(state.portfolioId(), unused -> new java.util.HashSet<>())
          .add(account.getId());
    }
    for (Account account : eligibleAtEnd) {
      AccountHistoryState state = stateOn(account, today, businessDateService.zone(), historyStates);
      idsAtEnd.computeIfAbsent(state.portfolioId(), unused -> new java.util.HashSet<>())
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
      Set<String> warnings) {
    List<AccountValuation> valuations = accountValuationService.valueAll(accounts, currency, asOf);
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
      estimated |=
          valuation.valueSourceDate() != null
              && valuation.valueSourceDate().isBefore(asOf)
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
              LocalDate created =
                  account.getCreatedAt() == null
                      ? null
                      : account.getCreatedAt().atZoneSameInstant(businessZone).toLocalDate();
              LocalDate inception = account.getOpenedAt() != null ? account.getOpenedAt() : created;
              LocalDate evidence = firstEvidence.get(account.getId());
              if (evidence != null && (inception == null || evidence.isBefore(inception))) {
                inception = evidence;
              }
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
                return "ACTIVE".equals(account.getStatus())
                    && (portfolioId == null
                        || portfolioId.equals(account.getPortfolio().getId()));
              }
              if (!state.verified() && warnings != null) {
                warnings.add(account.getName() + ": portfolio and archive history before tracking"
                    + " may have been restated");
              }
              return "ACTIVE".equals(state.status())
                  && (portfolioId == null || portfolioId.equals(state.portfolioId()));
            })
        .toList();
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
    BigDecimal amount = to.subtract(from);
    BigDecimal percent =
        from.signum() == 0
            ? null
            : amount.multiply(BigDecimal.valueOf(100)).divide(from.abs(), 2, RoundingMode.HALF_UP);
    return new Change(
        amount,
        percent,
        contributions,
        contributions == null ? null : amount.subtract(contributions));
  }

}
