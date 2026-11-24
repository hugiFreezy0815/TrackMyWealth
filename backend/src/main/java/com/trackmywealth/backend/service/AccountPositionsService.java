package com.trackmywealth.backend.service;

import com.trackmywealth.backend.dto.AccountPositionResponse;
import com.trackmywealth.backend.dto.AccountPositionsResponse;
import com.trackmywealth.backend.dto.AccountPositionsSource;
import com.trackmywealth.backend.dto.CostBasisSource;
import com.trackmywealth.backend.dto.CostFromOrders;
import com.trackmywealth.backend.dto.CurrencyConversionResult;
import com.trackmywealth.backend.dto.HoldingWithOrderCost;
import com.trackmywealth.backend.dto.NativeAccountValue;
import com.trackmywealth.backend.dto.ReportedAccountHoldings;
import com.trackmywealth.backend.dto.ReportedHoldingValue;
import com.trackmywealth.backend.dto.TradeLeg;
import com.trackmywealth.backend.dto.ValueBasisValues;
import com.trackmywealth.backend.dto.ValueStatus;
import com.trackmywealth.backend.entity.Account;
import com.trackmywealth.backend.entity.AccountSnapshot;
import com.trackmywealth.backend.entity.Security;
import com.trackmywealth.backend.entity.SnapshotHolding;
import com.trackmywealth.backend.error.ApiException;
import com.trackmywealth.backend.error.ProblemCode;
import com.trackmywealth.backend.repository.AccountSnapshotRepository;
import com.trackmywealth.backend.repository.SecurityRepository;
import com.trackmywealth.backend.repository.SnapshotHoldingRepository;
import com.trackmywealth.backend.security.AuthenticatedUserPrincipal;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * US-15-05: read the latest provider/manual snapshot with holdings, independently of newer
 * balance-only snapshots. Never infer a market price or a cost basis that was not reported - except
 * a cost basis the account's orders explain (US-15-15, {@link OrderCostBasisService}) for a
 * position the snapshot holds none for. Account access is checked before reading holdings (which
 * have no independent RLS policy).
 */
@Service
public class AccountPositionsService {

  private static final AccountPositionsSource SOURCE = AccountPositionsSource.HOLDINGS_SNAPSHOT;
  private static final String MANUAL = "MANUAL";

  private final AccountLookupService accountLookupService;
  private final AccessControlService accessControlService;
  private final AccountCurrencyService accountCurrencyService;
  private final AccountSnapshotRepository snapshotRepository;
  private final SnapshotHoldingRepository holdingRepository;
  private final SecurityRepository securityRepository;
  private final FxRateService fxRateService;
  private final OrderCostBasisService orderCostBasisService;
  private final BusinessDateService businessDates;
  private final String fxSource;

  public AccountPositionsService(
      AccountLookupService accountLookupService,
      AccessControlService accessControlService,
      AccountCurrencyService accountCurrencyService,
      AccountSnapshotRepository snapshotRepository,
      SnapshotHoldingRepository holdingRepository,
      SecurityRepository securityRepository,
      FxRateService fxRateService,
      OrderCostBasisService orderCostBasisService,
      BusinessDateService businessDates,
      @Value("${app.fx.default-source}") String fxSource) {
    this.accountLookupService = accountLookupService;
    this.accessControlService = accessControlService;
    this.accountCurrencyService = accountCurrencyService;
    this.snapshotRepository = snapshotRepository;
    this.holdingRepository = holdingRepository;
    this.securityRepository = securityRepository;
    this.fxRateService = fxRateService;
    this.orderCostBasisService = orderCostBasisService;
    this.businessDates = businessDates;
    this.fxSource = fxSource;
  }

  @Transactional(readOnly = true)
  public AccountPositionsResponse get(UUID accountId, AuthenticatedUserPrincipal actor) {
    Account account = accountLookupService.findAccountOrThrow(accountId, actor);
    accessControlService.requireAccountAccess(actor, account);
    if (!account.isHoldsPositions()) {
      throw new ApiException(
          ProblemCode.POSITIONS_NOT_APPLICABLE, "This account does not hold positions.");
    }
    String currency = accountCurrencyService.ownCurrency(account);
    LocalDate today = businessDates.today();
    List<AccountSnapshot> newest =
        snapshotRepository.findNewestWithHoldings(accountId, today, PageRequest.of(0, 1));
    if (newest.isEmpty()) {
      return new AccountPositionsResponse(accountId, currency, null, SOURCE, true, List.of());
    }

    AccountSnapshot snapshot = newest.get(0);
    List<SnapshotHolding> holdings = holdingRepository.findBySnapshotId(snapshot.getId());
    Map<UUID, Security> securities =
        securityRepository
            .findAllById(holdings.stream().map(SnapshotHolding::getSecurityId).toList())
            .stream()
            .collect(Collectors.toMap(Security::getId, Function.identity()));

    Map<String, Optional<CurrencyConversionResult>> quotes = new HashMap<>();
    Function<String, Optional<CurrencyConversionResult>> rates =
        from ->
            quotes.computeIfAbsent(
                from, key -> fxRateService.tryGetConversionRate(key, currency, today, fxSource));
    // US-15-15: the orders only matter for a position the snapshot holds no cost basis for.
    List<UUID> lacking =
        holdings.stream()
            .filter(holding -> holding.getReportedCostBasis() == null)
            .map(SnapshotHolding::getSecurityId)
            .distinct()
            .toList();
    Map<UUID, Map<UUID, List<TradeLeg>>> trades =
        lacking.isEmpty()
            ? Map.of()
            : orderCostBasisService.tradesFor(
                List.of(accountId), lacking, snapshot.getSnapshotDate());
    CostBasisSource recorded =
        MANUAL.equals(snapshot.getSource()) ? CostBasisSource.ENTERED : CostBasisSource.STATEMENT;
    List<AccountPositionResponse> positions = new ArrayList<>();
    for (SnapshotHolding holding : holdings) {
      Security security = securities.get(holding.getSecurityId());
      if (security == null) {
        throw new IllegalStateException("Snapshot holding refers to a missing security.");
      }
      Optional<CostFromOrders> fromOrders =
          holding.getReportedCostBasis() == null
              ? OrderCostBasisService.costBasis(
                  OrderCostBasisService.tradesOf(trades, accountId, holding.getSecurityId()),
                  holding.getSecurityId(),
                  snapshot.getSnapshotDate(),
                  holding.getQuantity(),
                  holding.getReportedCurrency())
              : Optional.empty();
      positions.add(toPosition(holding, security, currency, rates, recorded, fromOrders));
    }
    positions.sort(
        Comparator.comparing(
                AccountPositionResponse::valueInAccountCurrency,
                Comparator.nullsLast(Comparator.reverseOrder()))
            .thenComparing(AccountPositionResponse::securityId));
    boolean complete =
        positions.stream().allMatch(position -> position.valueInAccountCurrency() != null);
    return new AccountPositionsResponse(
        accountId, currency, snapshot.getSnapshotDate(), SOURCE, complete, positions);
  }

  /**
   * Read the statement components for accounts already authorized by the caller. With {@code
   * withOrderCost}, a position without a cost basis carries the one its orders give, if they
   * explain it (US-15-15) - only a caller that reads the cost (the depot gain, {@link #summary})
   * asks for it, so a value alone costs no order query.
   */
  Map<UUID, List<ReportedHoldingValue>> reportedHoldingsFor(
      List<UUID> accountIds, LocalDate asOf, boolean withOrderCost) {
    List<ReportedHoldingValue> components =
        snapshotRepository.findLatestReportedHoldings(accountIds, asOf);
    if (!withOrderCost) {
      return components.stream().collect(Collectors.groupingBy(ReportedHoldingValue::getAccountId));
    }
    List<ReportedHoldingValue> lacking =
        components.stream()
            .filter(component -> component.isHolding() && component.getReportedCostBasis() == null)
            .filter(component -> component.getSecurityId() != null)
            .toList();
    Map<UUID, Map<UUID, List<TradeLeg>>> trades =
        orderCostBasisService.tradesFor(
            lacking.stream().map(ReportedHoldingValue::getAccountId).distinct().toList(),
            lacking.stream().map(ReportedHoldingValue::getSecurityId).distinct().toList(),
            asOf);
    return components.stream()
        .map(component -> withOrderCost(component, trades))
        .collect(Collectors.groupingBy(ReportedHoldingValue::getAccountId));
  }

  private static ReportedHoldingValue withOrderCost(
      ReportedHoldingValue component, Map<UUID, Map<UUID, List<TradeLeg>>> trades) {
    UUID securityId = component.getSecurityId();
    BigDecimal quantity = component.getQuantity();
    if (!component.isHolding()
        || component.getReportedCostBasis() != null
        || securityId == null
        || quantity == null) {
      return component;
    }
    return OrderCostBasisService.costBasis(
            OrderCostBasisService.tradesOf(trades, component.getAccountId(), securityId),
            securityId,
            component.getSnapshotDate(),
            quantity,
            component.getReportedCurrency())
        .<ReportedHoldingValue>map(cost -> new HoldingWithOrderCost(component, cost))
        .orElse(component);
  }

  /** Convert each component directly to the target. An incomplete statement has no value. */
  Optional<NativeAccountValue> reportedValue(
      List<ReportedHoldingValue> components,
      String targetCurrency,
      Function<String, Optional<CurrencyConversionResult>> rates) {
    if (components.isEmpty() || components.get(0).isUnrecordedCash()) {
      return Optional.empty();
    }
    BigDecimal total = BigDecimal.ZERO;
    boolean stale = false;
    for (ReportedHoldingValue component : components) {
      BigDecimal amount = component.getReportedValue();
      String from = component.getReportedCurrency();
      CurrencyConversionResult rate = rate(amount, from, targetCurrency, rates);
      BigDecimal converted = inAccountCurrency(amount, from, targetCurrency, rate);
      if (converted == null) {
        return Optional.empty();
      }
      total = total.add(converted);
      stale |= rate != null && rate.stale();
    }
    LocalDate date = components.get(0).getSnapshotDate();
    return Optional.of(
        new NativeAccountValue(total, ValueBasisValues.LATEST_SNAPSHOT, date, date, stale));
  }

  /**
   * #440: one statement's positions count, its value in the account currency and the unrealised
   * gain of its positions in the account currency (statement cash excluded), from the same
   * components as {@link #reportedValue}. Without a value there is no gain; a position without a
   * known account-currency gain (no cost basis, or no cost in the account currency: unknown
   * currency, or a foreign cost without its purchase-rate cost, #527) leaves both gain figures null
   * rather than a partial sum (FR-PERF-018), and is counted by cause.
   */
  ReportedAccountHoldings summary(
      List<ReportedHoldingValue> components,
      String accountCurrency,
      Function<String, Optional<CurrencyConversionResult>> rates) {
    LocalDate date = components.get(0).getSnapshotDate();
    List<ReportedHoldingValue> holdings =
        components.stream().filter(ReportedHoldingValue::isHolding).toList();
    Optional<NativeAccountValue> value = reportedValue(components, accountCurrency, rates);
    if (value.isEmpty()) {
      return new ReportedAccountHoldings(date, holdings.size());
    }
    BigDecimal totalGain = BigDecimal.ZERO;
    BigDecimal totalCostBasis = BigDecimal.ZERO;
    int missingBasis = 0;
    int foreignBasis = 0;
    for (ReportedHoldingValue holding : holdings) {
      BigDecimal costBasis = holding.getReportedCostBasis();
      String from = holding.getReportedCurrency();
      BigDecimal valueInAccountCurrency =
          inAccountCurrency(
              holding.getReportedValue(),
              from,
              accountCurrency,
              rate(holding.getReportedValue(), from, accountCurrency, rates));
      // The depot total sums account-currency gains only: a foreign cost basis counts once its
      // cost at the purchase-date rate(s) is known (#527), never converted at today's rate.
      BigDecimal accountCost =
          accountCost(
              costBasis,
              holding.getReportedCostBasisCurrency(),
              holding.getCostBasisInAccountCurrency(),
              accountCurrency);
      BigDecimal gain = difference(valueInAccountCurrency, accountCost);
      if (costBasis == null) {
        missingBasis++;
      } else if (gain == null) {
        foreignBasis++;
      } else {
        totalGain = totalGain.add(gain);
        totalCostBasis = totalCostBasis.add(accountCost);
      }
    }
    boolean gainKnown = missingBasis == 0 && foreignBasis == 0;
    return new ReportedAccountHoldings(
        date,
        holdings.size(),
        value.get(),
        gainKnown ? totalGain : null,
        gainKnown ? gainPercent(totalGain, totalCostBasis) : null,
        missingBasis == 0 ? null : missingBasis,
        foreignBasis == 0 ? null : foreignBasis);
  }

  private AccountPositionResponse toPosition(
      SnapshotHolding holding,
      Security security,
      String accountCurrency,
      Function<String, Optional<CurrencyConversionResult>> rates,
      CostBasisSource recorded,
      Optional<CostFromOrders> fromOrders) {
    BigDecimal reported = holding.getReportedValue();
    String reportedCurrency = holding.getReportedCurrency();
    CurrencyConversionResult rate = rate(reported, reportedCurrency, accountCurrency, rates);
    BigDecimal inAccountCurrency =
        inAccountCurrency(reported, reportedCurrency, accountCurrency, rate);
    // US-15-15: the orders' cost basis, if any, stands in for the one the snapshot lacks; it has no
    // cost in the account currency (no FX is applied).
    CostFromOrders orders = fromOrders.orElse(null);
    BigDecimal costBasis = orders == null ? holding.getReportedCostBasis() : orders.costBasis();
    String costCurrency =
        orders == null ? holding.getReportedCostBasisCurrency() : orders.currency();
    BigDecimal costInAccountCurrency =
        orders == null ? holding.getCostBasisInAccountCurrency() : null;
    CostBasisSource source = sourceOf(costBasis, orders != null, recorded);
    // Calculated from recorded orders, never estimated - whatever the flag of the missing cost.
    boolean estimated = orders == null && holding.isCostBasisEstimated();

    // The gain in the cost basis' own currency: against the account-currency value for a cost in
    // the account currency, against the statement's value for a cost in the quoted currency.
    String gainCurrency = null;
    BigDecimal gain = null;
    if (accountCurrency.equals(costCurrency)) {
      gain = difference(inAccountCurrency, costBasis);
      gainCurrency = accountCurrency;
    } else if (costCurrency != null && costCurrency.equals(reportedCurrency)) {
      gain = difference(reported, costBasis);
      gainCurrency = reportedCurrency;
    }
    BigDecimal accountCost =
        accountCost(costBasis, costCurrency, costInAccountCurrency, accountCurrency);
    BigDecimal accountGain = difference(inAccountCurrency, accountCost);

    ValueStatus status = status(inAccountCurrency, rate, estimated);
    return new AccountPositionResponse(
        security.getId(),
        security.getDisplayName(),
        security.getIsin(),
        holding.getQuantity(),
        holding.getReportedPrice(),
        reportedCurrency,
        reported,
        inAccountCurrency,
        costBasis,
        costCurrency,
        costInAccountCurrency,
        estimated,
        source,
        gain,
        gain == null ? null : gainPercent(gain, costBasis),
        gain == null ? null : gainCurrency,
        accountGain,
        accountGain == null ? null : gainPercent(accountGain, accountCost),
        status);
  }

  /** US-15-15: where the shown cost basis comes from; null without one. */
  private static @Nullable CostBasisSource sourceOf(
      @Nullable BigDecimal costBasis, boolean fromOrders, CostBasisSource recorded) {
    if (fromOrders) {
      return CostBasisSource.ORDERS;
    }
    return costBasis == null ? null : recorded;
  }

  /**
   * #527: the position's cost in the account currency - its cost basis when that is in the account
   * currency, else the reported cost at the purchase-date rate(s); null when neither is known. A
   * cost basis of unknown currency (older rows) has none (FR-PERF-018).
   */
  private static @Nullable BigDecimal accountCost(
      @Nullable BigDecimal costBasis,
      @Nullable String costBasisCurrency,
      @Nullable BigDecimal costBasisInAccountCurrency,
      String accountCurrency) {
    if (costBasis == null || costBasisCurrency == null) {
      return null;
    }
    return accountCurrency.equals(costBasisCurrency) ? costBasis : costBasisInAccountCurrency;
  }

  /** Value minus cost, both in one currency; null when either is unknown. */
  private static @Nullable BigDecimal difference(
      @Nullable BigDecimal value, @Nullable BigDecimal cost) {
    if (value == null || cost == null) {
      return null;
    }
    return value.subtract(cost).setScale(FxRateService.MONEY_SCALE, RoundingMode.HALF_UP);
  }

  private static @Nullable BigDecimal gainPercent(BigDecimal gain, BigDecimal costBasis) {
    return costBasis.signum() == 0
        ? null
        : gain.multiply(BigDecimal.valueOf(100)).divide(costBasis.abs(), 2, RoundingMode.HALF_UP);
  }

  /**
   * Best to worst as in {@link ValueStatus#of}: an FX rate older than {@code app.fx.stale-after}
   * makes the value STALE (PR-011), an estimated cost basis makes it ESTIMATED.
   */
  private static ValueStatus status(
      @Nullable BigDecimal valueInAccountCurrency,
      @Nullable CurrencyConversionResult rate,
      boolean costBasisEstimated) {
    if (valueInAccountCurrency == null) {
      return ValueStatus.UNKNOWN;
    }
    if (rate != null && rate.stale()) {
      return ValueStatus.STALE;
    }
    return costBasisEstimated ? ValueStatus.ESTIMATED : ValueStatus.CURRENT;
  }

  /** The rate at the valuation date, or null when none is needed or none is known. */
  private static @Nullable CurrencyConversionResult rate(
      @Nullable BigDecimal value,
      @Nullable String from,
      String to,
      Function<String, Optional<CurrencyConversionResult>> rates) {
    if (value == null || from == null || from.equals(to)) {
      return null;
    }
    return rates.apply(from).orElse(null);
  }

  private @Nullable BigDecimal inAccountCurrency(
      @Nullable BigDecimal value,
      @Nullable String from,
      String to,
      @Nullable CurrencyConversionResult rate) {
    if (value == null || from == null) {
      return null;
    }
    if (from.equals(to)) {
      return value.setScale(FxRateService.MONEY_SCALE, RoundingMode.HALF_UP);
    }
    return rate == null ? null : fxRateService.applyRate(value, rate);
  }
}
