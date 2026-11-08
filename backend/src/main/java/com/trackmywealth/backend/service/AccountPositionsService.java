package com.trackmywealth.backend.service;

import com.trackmywealth.backend.dto.AccountPositionResponse;
import com.trackmywealth.backend.dto.AccountPositionsResponse;
import com.trackmywealth.backend.dto.AccountPositionsSource;
import com.trackmywealth.backend.dto.CurrencyConversionResult;
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
import java.util.List;
import java.util.Map;
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
 * balance-only snapshots. Never infer a market price or a cost basis that was not reported. Account
 * access is checked before reading holdings (which have no independent RLS policy).
 */
@Service
public class AccountPositionsService {

  private static final AccountPositionsSource SOURCE = AccountPositionsSource.HOLDINGS_SNAPSHOT;

  private final AccountLookupService accountLookupService;
  private final AccessControlService accessControlService;
  private final AccountCurrencyService accountCurrencyService;
  private final AccountSnapshotRepository snapshotRepository;
  private final SnapshotHoldingRepository holdingRepository;
  private final SecurityRepository securityRepository;
  private final FxRateService fxRateService;
  private final String fxSource;

  public AccountPositionsService(
      AccountLookupService accountLookupService,
      AccessControlService accessControlService,
      AccountCurrencyService accountCurrencyService,
      AccountSnapshotRepository snapshotRepository,
      SnapshotHoldingRepository holdingRepository,
      SecurityRepository securityRepository,
      FxRateService fxRateService,
      @Value("${app.fx.default-source}") String fxSource) {
    this.accountLookupService = accountLookupService;
    this.accessControlService = accessControlService;
    this.accountCurrencyService = accountCurrencyService;
    this.snapshotRepository = snapshotRepository;
    this.holdingRepository = holdingRepository;
    this.securityRepository = securityRepository;
    this.fxRateService = fxRateService;
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
    List<AccountSnapshot> newest =
        snapshotRepository.findNewestWithHoldings(accountId, PageRequest.of(0, 1));
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

    List<AccountPositionResponse> positions = new ArrayList<>();
    for (SnapshotHolding holding : holdings) {
      Security security = securities.get(holding.getSecurityId());
      if (security == null) {
        throw new IllegalStateException("Snapshot holding refers to a missing security.");
      }
      positions.add(toPosition(holding, security, currency, snapshot.getSnapshotDate()));
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

  private AccountPositionResponse toPosition(
      SnapshotHolding holding, Security security, String accountCurrency, LocalDate asOf) {
    BigDecimal reported = holding.getReportedValue();
    String reportedCurrency = holding.getReportedCurrency();
    CurrencyConversionResult rate = rate(reported, reportedCurrency, accountCurrency, asOf);
    BigDecimal inAccountCurrency =
        inAccountCurrency(reported, reportedCurrency, accountCurrency, rate);
    BigDecimal costBasis = holding.getReportedCostBasis();
    BigDecimal gain =
        unrealisedGain(inAccountCurrency, reportedCurrency, accountCurrency, costBasis);
    BigDecimal gainPercent =
        gain == null || costBasis.signum() == 0
            ? null
            : gain.multiply(BigDecimal.valueOf(100))
                .divide(costBasis.abs(), 2, RoundingMode.HALF_UP);

    ValueStatus status = status(inAccountCurrency, rate, holding.isCostBasisEstimated());
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
        holding.isCostBasisEstimated(),
        gain,
        gainPercent,
        status);
  }

  /**
   * The reported cost basis carries no currency of its own (V11): a statement may print it in the
   * security's or in the account's currency. Only when both are the same is the gain certain;
   * otherwise there is no figure rather than a wrong one (FR-PERF-018).
   */
  private static @Nullable BigDecimal unrealisedGain(
      @Nullable BigDecimal valueInAccountCurrency,
      @Nullable String reportedCurrency,
      String accountCurrency,
      @Nullable BigDecimal costBasis) {
    if (valueInAccountCurrency == null
        || costBasis == null
        || !accountCurrency.equals(reportedCurrency)) {
      return null;
    }
    return valueInAccountCurrency
        .subtract(costBasis)
        .setScale(FxRateService.MONEY_SCALE, RoundingMode.HALF_UP);
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

  /** The rate at the snapshot date, or null when none is needed or none is known. */
  private @Nullable CurrencyConversionResult rate(
      @Nullable BigDecimal value, @Nullable String from, String to, LocalDate asOf) {
    if (value == null || from == null || from.equals(to)) {
      return null;
    }
    return fxRateService.tryGetConversionRate(from, to, asOf, fxSource).orElse(null);
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
