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
import com.trackmywealth.backend.dto.RecordAccountSnapshotRequest;
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
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
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
  private final AccountPositionsService service =
      new AccountPositionsService(
          accountLookup, access, currencies, snapshots, holdings, securities, fx, "ECB");

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
    when(snapshots.findNewestWithHoldings(eq(accountId), any(Pageable.class)))
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

  @Test
  void aForeignHoldingIsConvertedButGetsNoGainBecauseTheCostBasisHasNoCurrency() {
    allowedDepot("CHF");
    holding("Vanguard Total World", "100", "USD", "75");
    CurrencyConversionResult rate = rate("0.90", false);
    when(fx.tryGetConversionRate("USD", "CHF", AS_OF, "ECB")).thenReturn(Optional.of(rate));
    when(fx.applyRate(any(), eq(rate))).thenReturn(new BigDecimal("90.0000"));

    AccountPositionsResponse result = read();
    AccountPositionResponse position = result.positions().get(0);

    assertThat(result.complete()).isTrue();
    assertThat(position.value()).isEqualByComparingTo("100");
    assertThat(position.valueInAccountCurrency()).isEqualByComparingTo("90");
    assertThat(position.valueStatus()).isEqualTo(ValueStatus.CURRENT);
    assertThat(position.costBasis()).isEqualByComparingTo("75");
    assertThat(position.unrealisedGain()).isNull();
    assertThat(position.unrealisedGainPercent()).isNull();
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

  // --- Helpers ------------------------------------------------------------------------------

  private void allowedDepot(String currency) {
    Account account = mock(Account.class);
    when(accountLookup.findAccountOrThrow(accountId, actor)).thenReturn(account);
    when(account.isHoldsPositions()).thenReturn(true);
    when(currencies.ownCurrency(account)).thenReturn(currency);
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
    when(snapshots.findNewestWithHoldings(eq(accountId), any(Pageable.class)))
        .thenReturn(List.of(snapshot));
    when(holdings.findBySnapshotId(snapshotId)).thenReturn(List.copyOf(rows));
    when(securities.findAllById(any())).thenReturn(List.copyOf(known));
    return service.get(accountId, actor);
  }

  private static CurrencyConversionResult rate(String rate, boolean stale) {
    return new CurrencyConversionResult(
        new BigDecimal(rate), "USD", "CHF", AS_OF, true, null, stale, stale);
  }
}
