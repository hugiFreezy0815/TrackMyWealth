package com.trackmywealth.backend.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.trackmywealth.backend.dto.AccountPositionResponse;
import com.trackmywealth.backend.dto.AccountPositionsResponse;
import com.trackmywealth.backend.dto.CurrencyConversionResult;
import com.trackmywealth.backend.dto.NativeAccountValue;
import com.trackmywealth.backend.dto.RecordAccountSnapshotRequest;
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
import com.trackmywealth.backend.repository.TransactionRepository;
import com.trackmywealth.backend.security.AuthenticatedUserPrincipal;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;

/** Pure unit tests for the read model and business rules of US-15-05 (#478). */
class AccountPositionsServiceTest {

  private static final LocalDate AS_OF = LocalDate.of(2026, 9, 30);

  private final AccountLookupService accountLookup = mock(AccountLookupService.class);
  private final AccessControlService access = mock(AccessControlService.class);
  private final AccountCurrencyService currencies = mock(AccountCurrencyService.class);
  private final AccountSnapshotRepository snapshots = mock(AccountSnapshotRepository.class);
  private final SnapshotHoldingRepository holdings = mock(SnapshotHoldingRepository.class);
  private final SecurityRepository securities = mock(SecurityRepository.class);
  private final FxRateService fx = mock(FxRateService.class);
  private final AuthenticatedUserPrincipal actor = mock(AuthenticatedUserPrincipal.class);
  private final BusinessDateService businessDates = mock(BusinessDateService.class);
  private final TransactionRepository transactions = mock(TransactionRepository.class);
  private final AccountPositionsService service =
      new AccountPositionsService(
          accountLookup,
          access,
          currencies,
          snapshots,
          holdings,
          securities,
          fx,
          new OrderCostBasisService(transactions),
          businessDates,
          "ECB");

  private final UUID accountId = UUID.randomUUID();
  private final List<SnapshotHolding> rows = new ArrayList<>();
  private final List<Security> known = new ArrayList<>();

  @Test
  void anAccountWithoutPositionsIsRejectedBeforeHoldingsCanBeRead() {
    Account account = mock(Account.class);
    when(accountLookup.findAccountOrThrow(accountId, actor)).thenReturn(account);
    when(account.isHoldsPositions()).thenReturn(false);

    assertThatThrownBy(() -> service.get(accountId, actor))
        .isInstanceOfSatisfying(
            ApiException.class,
            problem ->
                assertThat(problem.getCode()).isEqualTo(ProblemCode.POSITIONS_NOT_APPLICABLE));

    verify(access).requireAccountAccess(actor, account);
    verifyNoInteractions(snapshots, holdings, securities, fx);
  }

  @Test
  void noSnapshotReturnsACompleteEmptyPositionsResponse() {
    allowedDepot("CHF");
    when(snapshots.findNewestWithHoldings(eq(accountId), eq(AS_OF), any(Pageable.class)))
        .thenReturn(List.of());

    AccountPositionsResponse result = service.get(accountId, actor);

    assertThat(result.asOf()).isNull();
    assertThat(result.positions()).isEmpty();
    assertThat(result.complete()).isTrue();
    verifyNoInteractions(holdings, securities, fx);
  }

  @Test
  void aKnownValueWithoutCostBasisNeverInventsAnUnrealisedGain() {
    allowedDepot("CHF");
    holding("World ETF", "100.00", "CHF", null);

    AccountPositionsResponse result = read();
    AccountPositionResponse position = result.positions().get(0);

    assertThat(result.complete()).isTrue();
    assertThat(result.asOf()).isEqualTo(AS_OF);
    assertThat(position.valueInAccountCurrency()).isEqualByComparingTo("100.00");
    assertThat(position.costBasis()).isNull();
    assertThat(position.unrealisedGain()).isNull();
    assertThat(position.unrealisedGainPercent()).isNull();
    assertThat(position.valueStatus()).isEqualTo(ValueStatus.CURRENT);
    verifyNoInteractions(fx);
  }

  @Test
  void aGainInTheAccountCurrencyIsRoundedToTwoDecimalsInPercent() {
    allowedDepot("EUR");
    holding("iShares Core MSCI World", "30512.55", "EUR", "27443.45");

    AccountPositionResponse position = read().positions().get(0);

    assertThat(position.unrealisedGain()).isEqualByComparingTo("3069.10");
    assertThat(position.unrealisedGain().scale()).isEqualTo(FxRateService.MONEY_SCALE);
    assertThat(position.unrealisedGainPercent()).isEqualByComparingTo("11.18");
  }

  @Test
  void aZeroCostBasisHasAGainButNoPercent() {
    allowedDepot("CHF");
    holding("Gifted shares", "40", "CHF", "0");

    AccountPositionResponse position = read().positions().get(0);

    assertThat(position.unrealisedGain()).isEqualByComparingTo("40");
    assertThat(position.unrealisedGainPercent()).isNull();
  }

  // #527 AC 1: a USD cost basis gives the gain in USD - value minus cost, both as the statement
  // printed them. Without its cost at the purchase-date rate there is no CHF gain.
  @Test
  void aCostBasisInTheSecuritysCurrencyGivesTheGainInThatCurrency() {
    allowedDepot("CHF");
    holding("Vanguard Total World", "100", "USD", "75");
    usdToChfAt("0.90", "90.0000");

    AccountPositionsResponse result = read();
    AccountPositionResponse position = result.positions().get(0);

    assertThat(result.complete()).isTrue();
    assertThat(position.valueInAccountCurrency()).isEqualByComparingTo("90");
    assertThat(position.valueStatus()).isEqualTo(ValueStatus.CURRENT);
    assertThat(position.costBasisCurrency()).isEqualTo("USD");
    assertThat(position.unrealisedGain()).isEqualByComparingTo("25");
    assertThat(position.unrealisedGainCurrency()).isEqualTo("USD");
    assertThat(position.unrealisedGainPercent()).isEqualByComparingTo("33.33");
    assertThat(position.unrealisedGainInAccountCurrency()).isNull();
    assertThat(position.unrealisedGainInAccountCurrencyPercent()).isNull();
  }

  // #527 AC 2: a CHF cost basis of a USD position gives the gain in CHF, against today's value.
  @Test
  void aCostBasisInTheAccountCurrencyGivesTheGainInTheAccountCurrency() {
    allowedDepot("CHF");
    holding("Vanguard Total World", "100", "USD", "70").setReportedCostBasisCurrency("CHF");
    usdToChfAt("0.90", "90.0000");

    AccountPositionResponse position = read().positions().get(0);

    assertThat(position.unrealisedGain()).isEqualByComparingTo("20");
    assertThat(position.unrealisedGainCurrency()).isEqualTo("CHF");
    assertThat(position.unrealisedGainInAccountCurrency()).isEqualByComparingTo("20");
    assertThat(position.unrealisedGainInAccountCurrencyPercent()).isEqualByComparingTo("28.57");
  }

  // PO 2026-11-23: the cost at the purchase-date rate gives the CHF gain beside the USD one; it
  // includes the FX change since purchase, which today's rate would leave out.
  @Test
  void theCostAtThePurchaseRateGivesTheAccountCurrencyGainBesideTheSecurityCurrencyGain() {
    allowedDepot("CHF");
    holding("Vanguard Total World", "100", "USD", "75")
        .setCostBasisInAccountCurrency(new BigDecimal("72.0000"));
    usdToChfAt("0.90", "90.0000");

    AccountPositionResponse position = read().positions().get(0);

    assertThat(position.unrealisedGain()).isEqualByComparingTo("25");
    assertThat(position.unrealisedGainCurrency()).isEqualTo("USD");
    assertThat(position.costBasisInAccountCurrency()).isEqualByComparingTo("72");
    assertThat(position.unrealisedGainInAccountCurrency()).isEqualByComparingTo("18");
    assertThat(position.unrealisedGainInAccountCurrencyPercent()).isEqualByComparingTo("25.00");
  }

  // #527 AC 3: a cost basis of unknown currency (rows before V125) has no gain at all.
  @Test
  void aCostBasisOfUnknownCurrencyHasNoGain() {
    allowedDepot("CHF");
    holding("Vanguard Total World", "100", "USD", "75").setReportedCostBasisCurrency(null);
    usdToChfAt("0.90", "90.0000");

    AccountPositionResponse position = read().positions().get(0);

    assertThat(position.costBasis()).isEqualByComparingTo("75");
    assertThat(position.costBasisCurrency()).isNull();
    assertThat(position.unrealisedGain()).isNull();
    assertThat(position.unrealisedGainPercent()).isNull();
    assertThat(position.unrealisedGainCurrency()).isNull();
    assertThat(position.unrealisedGainInAccountCurrency()).isNull();
  }

  // #527 AC 5: no gain mixes two currencies - a cost basis in a third currency is neither
  // compared with the USD value nor with the CHF one.
  @Test
  void aCostBasisInAThirdCurrencyIsNeverComparedWithAValueInAnother() {
    allowedDepot("CHF");
    holding("Vanguard Total World", "100", "USD", "75").setReportedCostBasisCurrency("EUR");
    usdToChfAt("0.90", "90.0000");

    AccountPositionResponse position = read().positions().get(0);

    assertThat(position.unrealisedGain()).isNull();
    assertThat(position.unrealisedGainCurrency()).isNull();
    assertThat(position.unrealisedGainInAccountCurrency()).isNull();
  }

  @Test
  void aMissingRateMakesTheValueUnknownAndTheListIncomplete() {
    allowedDepot("CHF");
    holding("Vanguard Total World", "100", "USD", null);
    when(fx.tryGetConversionRate("USD", "CHF", AS_OF, "ECB")).thenReturn(Optional.empty());

    AccountPositionsResponse result = read();

    assertThat(result.complete()).isFalse();
    assertThat(result.positions().get(0).value()).isEqualByComparingTo("100");
    assertThat(result.positions().get(0).valueInAccountCurrency()).isNull();
    assertThat(result.positions().get(0).valueStatus()).isEqualTo(ValueStatus.UNKNOWN);
  }

  @Test
  void aStaleRateMakesTheValueStaleEvenWithAnEstimatedCostBasis() {
    allowedDepot("CHF");
    holding("Vanguard Total World", "100", "USD", "75").setCostBasisEstimated(true);
    CurrencyConversionResult rate = rate("0.90", true);
    when(fx.tryGetConversionRate("USD", "CHF", AS_OF, "ECB")).thenReturn(Optional.of(rate));
    when(fx.applyRate(any(), eq(rate))).thenReturn(new BigDecimal("90.0000"));

    assertThat(read().positions().get(0).valueStatus()).isEqualTo(ValueStatus.STALE);
  }

  @Test
  void anEstimatedCostBasisIsEstimatedUnlessTheValueIsUnknown() {
    allowedDepot("CHF");
    holding("Estimated", "10", "CHF", "8").setCostBasisEstimated(true);
    holding("Unknown", null, null, "8").setCostBasisEstimated(true);

    List<AccountPositionResponse> positions = read().positions();

    assertThat(positions)
        .extracting(AccountPositionResponse::valueStatus)
        .containsExactly(ValueStatus.ESTIMATED, ValueStatus.UNKNOWN);
    assertThat(positions).allMatch(AccountPositionResponse::costBasisEstimated);
  }

  @Test
  void positionsAreSortedByAccountValueDescendingUnknownLastThenBySecurityId() {
    allowedDepot("CHF");
    SnapshotHolding small = holding("Small", "10", "CHF", null);
    SnapshotHolding unknown = holding("Unknown", null, null, null);
    SnapshotHolding tieA = holding("Tie A", "50", "CHF", null);
    SnapshotHolding tieB = holding("Tie B", "50.0", "CHF", null);
    SnapshotHolding large = holding("Large", "500", "CHF", null);
    UUID firstTie =
        tieA.getSecurityId().compareTo(tieB.getSecurityId()) < 0
            ? tieA.getSecurityId()
            : tieB.getSecurityId();
    UUID secondTie =
        firstTie.equals(tieA.getSecurityId()) ? tieB.getSecurityId() : tieA.getSecurityId();

    AccountPositionsResponse result = read();

    assertThat(result.positions())
        .extracting(AccountPositionResponse::securityId)
        .containsExactly(
            large.getSecurityId(),
            firstTie,
            secondTie,
            small.getSecurityId(),
            unknown.getSecurityId());
    assertThat(result.complete()).isFalse();
  }

  @Test
  void aHoldingOfAnUnknownSecurityIsAnInconsistencyNotAnEmptyRow() {
    allowedDepot("CHF");
    holding("Gone", "10", "CHF", null);
    known.clear();

    assertThatThrownBy(this::read).isInstanceOf(IllegalStateException.class);
  }

  // The positions list is not paged, so its documented cap must cover every way holdings arrive.
  @Test
  void theDocumentedCapCoversImportedAndManualHoldings() throws NoSuchFieldException {
    int manual =
        RecordAccountSnapshotRequest.class
            .getDeclaredField("holdings")
            .getAnnotation(Size.class)
            .max();

    assertThat(AccountPositionsResponse.MAX_POSITIONS)
        .isGreaterThanOrEqualTo(ImportHoldingsReaderService.MAX_LINES)
        .isGreaterThanOrEqualTo(manual);
  }

  // --- #586: account value from the reported holdings ----------------------------------------

  @Test
  void aCompleteStatementIsWorthItsCashPlusEveryConvertedHolding() {
    CurrencyConversionResult rate = rate("0.90", false);
    when(fx.tryGetConversionRate("USD", "CHF", AS_OF, "ECB")).thenReturn(Optional.of(rate));
    when(fx.applyRate(new BigDecimal("2000"), rate)).thenReturn(new BigDecimal("1800.0000"));
    when(fx.applyRate(new BigDecimal("500"), rate)).thenReturn(new BigDecimal("450.0000"));
    statement(
        reported(false, "1000", "CHF"),
        reported(false, "5000", "CHF"),
        reported(false, "2000", "USD"),
        reported(false, "500", "USD"));

    NativeAccountValue value = reportedValue().orElseThrow();

    assertThat(value.amount()).isEqualByComparingTo("8250");
    assertThat(value.basis()).isEqualTo(ValueBasisValues.LATEST_SNAPSHOT);
    assertThat(value.sourceDate()).isEqualTo(AS_OF);
    assertThat(value.confirmedOn()).isEqualTo(AS_OF);
    assertThat(value.sourceFxStale()).isFalse();
    // One rate lookup per currency pair and statement date, not per holding.
    verify(fx).tryGetConversionRate("USD", "CHF", AS_OF, "ECB");
  }

  @Test
  void aStaleRateMarksTheValueStale() {
    CurrencyConversionResult rate = rate("0.90", true);
    when(fx.tryGetConversionRate("USD", "CHF", AS_OF, "ECB")).thenReturn(Optional.of(rate));
    when(fx.applyRate(any(), eq(rate))).thenReturn(new BigDecimal("90.0000"));
    statement(reported(false, "0", "CHF"), reported(false, "100", "USD"));

    assertThat(reportedValue().orElseThrow().sourceFxStale()).isTrue();
  }

  @Test
  void aStatementMissingAnyFigureHasNoValueRatherThanAPartialSum() {
    statement(reported(false, null, "CHF"), reported(false, "5000", "CHF"));
    assertThat(reportedValue()).as("no cash balance").isEmpty();

    statement(reported(true, "1000", "CHF"), reported(true, "5000", "CHF"));
    assertThat(reportedValue()).as("unrecorded foreign cash").isEmpty();

    statement(reported(false, "5000", "CHF"), reported(false, null, null));
    assertThat(reportedValue()).as("a holding without a value").isEmpty();

    when(fx.tryGetConversionRate("USD", "CHF", AS_OF, "ECB")).thenReturn(Optional.empty());
    statement(reported(false, "5000", "CHF"), reported(false, "10", "USD"));
    assertThat(reportedValue()).as("no rate").isEmpty();
  }

  // --- #440: unrealised gain from the same statement ------------------------------------------

  @Test
  void theGainSumsThePositionGainsWithoutCashAsAPercentOfTheirCostBasis() {
    statement(
        reported(false, "700", "CHF"),
        position("15000", "CHF", "11800"),
        position("8000", "CHF", "10000"));

    ReportedAccountHoldings summary = summary();

    assertThat(summary.positionsCount()).as("cash is no position").isEqualTo(2);
    assertThat(summary.value().amount()).isEqualByComparingTo("23700");
    // A gain and a loss net out: 3,200 - 2,000 = 1,200 of 21,800 cost basis = 5.5045 %.
    assertThat(summary.totalReturn()).isEqualByComparingTo("1200");
    assertThat(summary.totalReturnPercent()).isEqualByComparingTo("5.50");
    assertThat(summary.missingCostBasisCount()).isNull();
    assertThat(summary.foreignCurrencyCostBasisCount()).isNull();
  }

  @Test
  void aZeroTotalCostBasisKeepsTheGainButHasNoPercentage() {
    statement(reported(false, "0", "CHF"), position("50", "CHF", "0"));

    ReportedAccountHoldings summary = summary();

    assertThat(summary.totalReturn()).isEqualByComparingTo("50");
    assertThat(summary.totalReturnPercent()).isNull();
  }

  @Test
  void anyPositionWithoutAKnownGainLeavesNoPartialSumButCountsEachCause() {
    CurrencyConversionResult rate = rate("0.90", false);
    when(fx.tryGetConversionRate("USD", "CHF", AS_OF, "ECB")).thenReturn(Optional.of(rate));
    when(fx.applyRate(any(), eq(rate))).thenReturn(new BigDecimal("90.0000"));
    statement(
        reported(false, "0", "CHF"),
        position("500", "CHF", "410"),
        position("600", "CHF", null),
        position("100", "USD", "80"));

    ReportedAccountHoldings summary = summary();

    assertThat(summary.value()).isNotNull();
    assertThat(summary.totalReturn()).isNull();
    assertThat(summary.totalReturnPercent()).isNull();
    assertThat(summary.missingCostBasisCount()).isEqualTo(1);
    assertThat(summary.foreignCurrencyCostBasisCount()).isEqualTo(1);
  }

  // US-15-15: the depot total counts a position without a cost basis once its orders explain it.
  @Test
  void aPositionWithoutACostBasisCountsTowardsTheDepotGainWithItsOrdersCost() {
    UUID security = UUID.randomUUID();
    ReportedHoldingValue lacking =
        new Line(
            accountId,
            AS_OF,
            false,
            new BigDecimal("600"),
            "CHF",
            true,
            null,
            null,
            null,
            security,
            BigDecimal.TEN);
    statement(reported(false, "0", "CHF"), position("500", "CHF", "410"), lacking);
    when(transactions.findTradesAsOf(List.of(accountId), AS_OF))
        .thenReturn(
            List.of(
                new TradeLeg(
                    accountId, security, BigDecimal.TEN, new BigDecimal("52"), "CHF", AS_OF)));

    ReportedAccountHoldings summary = summary();

    // (500 - 410) + (600 - 520) = 170 of 930 cost = 18.28 %.
    assertThat(summary.missingCostBasisCount()).isNull();
    assertThat(summary.totalReturn()).isEqualByComparingTo("170");
    assertThat(summary.totalReturnPercent()).isEqualByComparingTo("18.28");
  }

  // PO 2026-11-23 (#527): a foreign position counts towards the depot's gain with its cost at the
  // purchase-date rate - its CHF value today minus that cost, never its USD gain converted today.
  @Test
  void aForeignPositionWithItsPurchaseRateCostCountsTowardsTheDepotGain() {
    usdToChfAt("0.90", "90.0000");
    statement(
        reported(false, "0", "CHF"),
        position("500", "CHF", "410"),
        new Line(
            accountId,
            AS_OF,
            false,
            new BigDecimal("100"),
            "USD",
            true,
            new BigDecimal("80"),
            "USD",
            new BigDecimal("72"),
            UUID.randomUUID(),
            BigDecimal.ONE));

    ReportedAccountHoldings summary = summary();

    // (500 - 410) + (90 - 72) = 108 of 482 cost = 22.41 %.
    assertThat(summary.totalReturn()).isEqualByComparingTo("108");
    assertThat(summary.totalReturnPercent()).isEqualByComparingTo("22.41");
    assertThat(summary.foreignCurrencyCostBasisCount()).isNull();
  }

  @Test
  void anUnknownValueKeepsThePositionsCountButNoGainOrCauses() {
    statement(reported(false, null, "CHF"), position("500", "CHF", null));

    ReportedAccountHoldings summary = summary();

    assertThat(summary.positionsCount()).isEqualTo(1);
    assertThat(summary.snapshotDate()).isEqualTo(AS_OF);
    assertThat(summary.value()).isNull();
    assertThat(summary.totalReturn()).isNull();
    assertThat(summary.missingCostBasisCount()).isNull();
  }

  @Test
  void anAccountWithoutAHoldingsStatementIsLeftOut() {
    statement();

    assertThat(reportedValue()).isEmpty();
  }

  // --- Helpers ------------------------------------------------------------------------------

  private void usdToChfAt(String rate, String converted) {
    CurrencyConversionResult result = rate(rate, false);
    when(fx.tryGetConversionRate("USD", "CHF", AS_OF, "ECB")).thenReturn(Optional.of(result));
    when(fx.applyRate(any(), eq(result))).thenReturn(new BigDecimal(converted));
  }

  private void allowedDepot(String currency) {
    Account account = mock(Account.class);
    when(accountLookup.findAccountOrThrow(accountId, actor)).thenReturn(account);
    when(account.isHoldsPositions()).thenReturn(true);
    when(currencies.ownCurrency(account)).thenReturn(currency);
    when(businessDates.today()).thenReturn(AS_OF);
  }

  private SnapshotHolding holding(String name, String value, String currency, String costBasis) {
    UUID securityId = UUID.randomUUID();
    SnapshotHolding holding = new SnapshotHolding();
    holding.setSecurityId(securityId);
    holding.setQuantity(BigDecimal.TEN);
    if (value != null) {
      holding.setReported(null, new BigDecimal(value), currency);
    }
    if (costBasis != null) {
      holding.setReportedCostBasis(new BigDecimal(costBasis).setScale(4, RoundingMode.UNNECESSARY));
      // #527: as the statement prints it, in the position's own currency unless a test says other.
      holding.setReportedCostBasisCurrency(currency);
    }
    rows.add(holding);
    Security security = mock(Security.class);
    when(security.getId()).thenReturn(securityId);
    when(security.getDisplayName()).thenReturn(name);
    known.add(security);
    return holding;
  }

  private AccountPositionsResponse read() {
    UUID snapshotId = UUID.randomUUID();
    AccountSnapshot snapshot = mock(AccountSnapshot.class);
    when(snapshot.getId()).thenReturn(snapshotId);
    when(snapshot.getSnapshotDate()).thenReturn(AS_OF);
    when(snapshots.findNewestWithHoldings(eq(accountId), eq(AS_OF), any(Pageable.class)))
        .thenReturn(List.of(snapshot));
    when(holdings.findBySnapshotId(snapshotId)).thenReturn(List.copyOf(rows));
    when(securities.findAllById(any())).thenReturn(List.copyOf(known));
    return service.get(accountId, actor);
  }

  private void statement(ReportedHoldingValue... lines) {
    when(snapshots.findLatestReportedHoldings(List.of(accountId), AS_OF))
        .thenReturn(List.of(lines));
  }

  private Optional<NativeAccountValue> reportedValue() {
    return service.reportedValue(components(), "CHF", chfRates());
  }

  private ReportedAccountHoldings summary() {
    return service.summary(components(), "CHF", chfRates());
  }

  private List<ReportedHoldingValue> components() {
    return service
        .reportedHoldingsFor(List.of(accountId), AS_OF)
        .getOrDefault(accountId, List.of());
  }

  private Function<String, Optional<CurrencyConversionResult>> chfRates() {
    Map<String, Optional<CurrencyConversionResult>> rates = new HashMap<>();
    return from ->
        rates.computeIfAbsent(
            from, currency -> fx.tryGetConversionRate(currency, "CHF", AS_OF, "ECB"));
  }

  private ReportedHoldingValue reported(boolean unrecordedCash, String value, String currency) {
    return new Line(
        accountId,
        AS_OF,
        unrecordedCash,
        value == null ? null : new BigDecimal(value),
        currency,
        false,
        null,
        null,
        null,
        null,
        null);
  }

  private ReportedHoldingValue position(String value, String currency, String costBasis) {
    return new Line(
        accountId,
        AS_OF,
        false,
        new BigDecimal(value),
        currency,
        true,
        costBasis == null ? null : new BigDecimal(costBasis),
        // In the quoted currency: certain for a position in the account currency, without a
        // purchase-rate cost for any other (#527).
        costBasis == null ? null : currency,
        null,
        UUID.randomUUID(),
        BigDecimal.ONE);
  }

  private record Line(
      UUID accountId,
      LocalDate snapshotDate,
      boolean unrecordedCash,
      BigDecimal reportedValue,
      String reportedCurrency,
      boolean holding,
      BigDecimal reportedCostBasis,
      String reportedCostBasisCurrency,
      BigDecimal costBasisInAccountCurrency,
      UUID securityId,
      BigDecimal quantity)
      implements ReportedHoldingValue {

    @Override
    public UUID getSecurityId() {
      return securityId;
    }

    @Override
    public BigDecimal getQuantity() {
      return quantity;
    }

    @Override
    public String getReportedCostBasisCurrency() {
      return reportedCostBasisCurrency;
    }

    @Override
    public BigDecimal getCostBasisInAccountCurrency() {
      return costBasisInAccountCurrency;
    }

    @Override
    public boolean isHolding() {
      return holding;
    }

    @Override
    public BigDecimal getReportedCostBasis() {
      return reportedCostBasis;
    }

    @Override
    public UUID getAccountId() {
      return accountId;
    }

    @Override
    public LocalDate getSnapshotDate() {
      return snapshotDate;
    }

    @Override
    public boolean isUnrecordedCash() {
      return unrecordedCash;
    }

    @Override
    public BigDecimal getReportedValue() {
      return reportedValue;
    }

    @Override
    public String getReportedCurrency() {
      return reportedCurrency;
    }
  }

  private static CurrencyConversionResult rate(String rate, boolean stale) {
    return new CurrencyConversionResult(
        new BigDecimal(rate), "USD", "CHF", AS_OF, true, null, stale, stale);
  }
}
