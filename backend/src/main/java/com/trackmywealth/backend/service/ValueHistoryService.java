package com.trackmywealth.backend.service;

import com.trackmywealth.backend.dto.AccountValuation;
import com.trackmywealth.backend.dto.PortfolioPeriodChange;
import com.trackmywealth.backend.dto.PortfolioPlacement;
import com.trackmywealth.backend.dto.PortfolioSection;
import com.trackmywealth.backend.dto.SignedAmounts;
import com.trackmywealth.backend.dto.ValueBasisValues;
import com.trackmywealth.backend.dto.ValueHistoryResponse;
import com.trackmywealth.backend.dto.ValueHistoryResponse.Change;
import com.trackmywealth.backend.dto.ValueHistoryResponse.Point;
import com.trackmywealth.backend.dto.ValueHistoryResponse.SectionChange;
import com.trackmywealth.backend.entity.Account;
import com.trackmywealth.backend.entity.Portfolio;
import com.trackmywealth.backend.entity.Workspace;
import com.trackmywealth.backend.repository.AccountRepository;
import com.trackmywealth.backend.repository.ValueHistoryStartRepository;
import com.trackmywealth.backend.repository.WorkspaceRepository;
import com.trackmywealth.backend.security.AuthenticatedUserPrincipal;
import com.trackmywealth.backend.validation.CurrencyCodes;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
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
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

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
      AccountValuationService accountValuationService,
      AccountService accountService,
      BusinessDateService businessDateService,
      CashFlowService cashFlowService,
      ValueHistoryStartRepository historyStartRepository) {
    this.accessControlService = accessControlService;
    this.portfolioLookupService = portfolioLookupService;
    this.workspaceRepository = workspaceRepository;
    this.accountRepository = accountRepository;
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
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Not found."));
    String currency = CurrencyCodes.requestedOrDefault(requestedCurrency, workspace.getCurrency());
    List<Account> accounts =
        accessControlService.accountsWithAccess(
            memberId, accountRepository.findByWorkspaceId(actor.workspaceId()));
    return history(periodParam, currency, accounts, actor, true);
  }

  @Transactional(readOnly = true)
  public ValueHistoryResponse portfolioHistory(
      UUID portfolioId, String periodParam, AuthenticatedUserPrincipal actor) {
    Portfolio portfolio = portfolioLookupService.findPortfolioOrThrow(portfolioId, actor);
    accessControlService.requirePortfolioAccess(actor, portfolio);
    List<Account> accounts =
        accountRepository.findByWorkspaceId(actor.workspaceId()).stream()
            .filter(account -> portfolioId.equals(account.getPortfolio().getId()))
            .toList();
    return history(periodParam, portfolio.getPortfolioCurrency(), accounts, actor, false);
  }

  private ValueHistoryResponse history(
      String periodParam,
      String currency,
      List<Account> accounts,
      AuthenticatedUserPrincipal actor,
      boolean withSections) {
    ValueHistoryPeriod period = ValueHistoryPeriod.parse(periodParam);
    LocalDate today = businessDateService.today();
    LocalDate from =
        period.start(today, historyStartRepository.firstActivity(actor.workspaceId(), today));
    List<LocalDate> dates = period.dates(from, today);
    Map<UUID, PortfolioPlacement> placements =
        withSections ? accountService.portfolioPlacementsOf(accounts) : Map.of();
    List<Point> points = new ArrayList<>(dates.size());
    Set<String> warnings = new TreeSet<>();
    Snapshot first = null;
    Snapshot last = null;
    boolean complete = true;
    for (LocalDate date : dates) {
      List<Account> eligible = onDate(accounts, date, businessDateService.zone());
      Snapshot snapshot = snapshot(eligible, currency, date, placements, warnings);
      points.add(new Point(date, snapshot.value(), snapshot.estimated()));
      complete &= snapshot.complete();
      if (first == null) {
        first = snapshot;
      }
      last = snapshot;
    }
    Snapshot start = Objects.requireNonNull(first);
    Snapshot end = Objects.requireNonNull(last);
    List<UUID> accountIds = accounts.stream().map(Account::getId).toList();
    BigDecimal contributions =
        cashFlowService.netContributions(accountIds, from.plusDays(1), today, currency);
    if (contributions == null) {
      complete = false;
      warnings.add("Currency conversion unavailable for contributions.");
    }
    Change change = change(start.value(), end.value(), contributions);
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
    List<Account> eligibleAtStart = onDate(accounts, from, businessDateService.zone());
    List<Account> eligibleAtEnd = onDate(accounts, today, businessDateService.zone());
    Map<UUID, List<Account>> groups = new HashMap<>();
    for (Account account : accounts) {
      groups
          .computeIfAbsent(account.getPortfolio().getId(), unused -> new ArrayList<>())
          .add(account);
    }
    var start = accountValuationService.valueAllInCurrencies(eligibleAtStart, currencies, from);
    var end = accountValuationService.valueAllInCurrencies(eligibleAtEnd, currencies, today);
    Map<UUID, PortfolioPeriodChange> result = new HashMap<>();
    for (Portfolio portfolio : portfolios) {
      UUID portfolioId = portfolio.getId();
      String currency = portfolio.getPortfolioCurrency();
      List<Account> group = groups.getOrDefault(portfolioId, List.of());
      List<AccountValuation> atStart =
          start.in(currency).stream()
              .filter(v -> group.stream().anyMatch(a -> a.getId().equals(v.accountId())))
              .toList();
      List<AccountValuation> atEnd =
          end.in(currency).stream()
              .filter(v -> group.stream().anyMatch(a -> a.getId().equals(v.accountId())))
              .toList();
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

  private Snapshot snapshot(
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
        warnings.add(valuation.name() + ": value unknown on " + asOf);
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
    return new Snapshot(sum(valuations), complete, estimated, Map.copyOf(sections));
  }

  private static List<Account> onDate(
      Collection<Account> accounts, LocalDate date, java.time.ZoneId businessZone) {
    return accounts.stream()
        .filter(account -> account.getOpenedAt() == null || !date.isBefore(account.getOpenedAt()))
        .filter(
            account ->
                account.getArchivedAt() == null
                    || date.isBefore(
                        account.getArchivedAt().atZoneSameInstant(businessZone).toLocalDate()))
        .toList();
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

  private record Snapshot(
      BigDecimal value,
      boolean complete,
      boolean estimated,
      Map<PortfolioSection, BigDecimal> sections) {}
}
