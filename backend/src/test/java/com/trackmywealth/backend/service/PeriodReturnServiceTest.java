package com.trackmywealth.backend.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.trackmywealth.backend.dto.AccountValuation;
import com.trackmywealth.backend.dto.DatedAmount;
import com.trackmywealth.backend.dto.DatedCashFlows;
import com.trackmywealth.backend.dto.PeriodReturn;
import com.trackmywealth.backend.dto.PeriodReturns;
import com.trackmywealth.backend.dto.ReturnScopeContext;
import com.trackmywealth.backend.dto.ReturnScopePlan;
import com.trackmywealth.backend.dto.ReturnTerm;
import com.trackmywealth.backend.dto.TrackingStartSources;
import com.trackmywealth.backend.entity.Account;
import com.trackmywealth.backend.repository.ValueHistoryStartRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * #607: known answers for the period return (methodology "Period return"), the tracking start per
 * value source, and which values and flows a scope reads. The end-to-end criteria are in {@code
 * NetWorthControllerTest}.
 */
class PeriodReturnServiceTest {

  private static final LocalDate TODAY = LocalDate.of(2026, 11, 20);
  private static final LocalDate YEAR_AGO = TODAY.minusYears(1);
  private static final UUID A = UUID.fromString("00000000-0000-0000-0000-00000000000a");
  private static final UUID B = UUID.fromString("00000000-0000-0000-0000-00000000000b");

  // --- Modified Dietz, the methodology's worked examples ---------------------------------------

  @Test
  void anAccountEnteringTrackingWithItsFirstBalanceIsNoGain() {
    // Example 1: A holds 2,000 all year, B enters with 1,000 thirty days ago.
    assertThat(
            PeriodReturnService.modifiedDietz(
                bd("2000"), bd("3000"), List.of(flow(30, "1000")), YEAR_AGO, TODAY))
        .isEqualByComparingTo("0.00");
  }

  @Test
  void growthIsMeasuredAgainstTheTimeWeightedCapital() {
    // Example 2: A grows to 2,100. 100 / (2,000 + 30/365 × 1,000) = 4.80 %.
    assertThat(
            PeriodReturnService.modifiedDietz(
                bd("2000"), bd("3100"), List.of(flow(30, "1000")), YEAR_AGO, TODAY))
        .isEqualByComparingTo("4.80");
  }

  @Test
  void netSavingIsAFlowNotAReturn() {
    // Example 4: salary 5,000 in, spending 3,000 out, no market change.
    assertThat(
            PeriodReturnService.modifiedDietz(
                bd("10000"),
                bd("12000"),
                List.of(flow(200, "5000"), flow(100, "-3000")),
                YEAR_AGO,
                TODAY))
        .isEqualByComparingTo("0.00");
  }

  @Test
  void aFlowOnTheLastDayHasNoWeightAndOneOnTheFirstDayHasFullWeight() {
    assertThat(
            PeriodReturnService.modifiedDietz(
                bd("1000"), bd("2100"), List.of(flow(0, "1000")), TODAY.minusDays(10), TODAY))
        .isEqualByComparingTo("10.00");
    assertThat(
            PeriodReturnService.modifiedDietz(
                bd("1000"), bd("2100"), List.of(flow(10, "1000")), TODAY.minusDays(10), TODAY))
        .isEqualByComparingTo("5.00");
  }

  @Test
  void noReturnWithoutCapitalOrWithoutTime() {
    assertThat(
            PeriodReturnService.modifiedDietz(
                BigDecimal.ZERO, BigDecimal.ZERO, List.of(), YEAR_AGO, TODAY))
        .isNull();
    assertThat(
            PeriodReturnService.modifiedDietz(bd("-5000"), bd("-4000"), List.of(), YEAR_AGO, TODAY))
        .isNull();
    assertThat(PeriodReturnService.modifiedDietz(bd("100"), bd("110"), List.of(), TODAY, TODAY))
        .isNull();
  }

  // --- Tracking start ----------------------------------------------------------------------------

  @Test
  void trackingStartsAtTheFirstRecordedBalanceOfTheAccountsValueSource() {
    LocalDate balance = TODAY.minusDays(40);
    LocalDate opening = TODAY.minusDays(30);
    LocalDate holdings = TODAY.minusDays(20);
    LocalDate valuation = TODAY.minusDays(10);
    LocalDate inception = TODAY.minusDays(90);
    TrackingStartSources all =
        new TrackingStartSources(A, balance, opening, holdings, valuation, false);

    assertThat(PeriodReturnService.trackingStart(manualValuation(), all, inception))
        .isEqualTo(valuation);
    assertThat(PeriodReturnService.trackingStart(depot(), all, inception)).isEqualTo(holdings);
    assertThat(PeriodReturnService.trackingStart(withoutLedger(), all, inception))
        .isEqualTo(balance);
    // A ledger's earlier statement balance does not make its value known: the opening balance does.
    assertThat(PeriodReturnService.trackingStart(ledger(), all, inception)).isEqualTo(opening);
    assertThat(PeriodReturnService.trackingStart(card(), all, inception)).isEqualTo(opening);
    assertThat(PeriodReturnService.trackingStart(loan(), all, inception)).isEqualTo(balance);
  }

  @Test
  void accountsValuedWithoutARecordedBalanceStartAtTheirInception() {
    LocalDate inception = TODAY.minusDays(90);
    TrackingStartSources none = new TrackingStartSources(A, null, null, null, null, false);
    TrackingStartSources principal = new TrackingStartSources(A, null, null, null, null, true);

    assertThat(PeriodReturnService.trackingStart(card(), none, inception)).isEqualTo(inception);
    assertThat(PeriodReturnService.trackingStart(loan(), principal, inception))
        .isEqualTo(inception);
    assertThat(PeriodReturnService.trackingStart(loan(), none, inception)).isNull();
    // An ordinary ledger without an opening balance has no known value: never tracked.
    assertThat(PeriodReturnService.trackingStart(ledger(), none, inception)).isNull();
    assertThat(PeriodReturnService.trackingStart(ledger(), null, inception)).isNull();
  }

  // --- Scope plans -------------------------------------------------------------------------------

  @Test
  void theReturnStartsAtTheScopesEarliestTrackedDayWhenThatIsLater() {
    LocalDate tracked = TODAY.minusDays(34);
    ReturnScopeContext context =
        new ReturnScopeContext(YEAR_AGO, TODAY, Map.of(A, tracked), Map.of(), (id, date) -> true);

    ReturnScopePlan plan = PeriodReturnService.plan(context, Set.of(A));

    assertThat(plan.start()).isEqualTo(tracked);
    assertThat(plan.terms())
        .containsExactlyInAnyOrder(
            new ReturnTerm(A, tracked, ReturnTerm.Kind.START),
            new ReturnTerm(A, TODAY, ReturnTerm.Kind.END));
  }

  @Test
  void anAccountTrackedInsideThePeriodIsAFlowAndALifecycleChangeMovesItsValue() {
    LocalDate archived = TODAY.minusDays(5);
    LocalDate restored = TODAY.minusDays(2);
    ReturnScopeContext context =
        new ReturnScopeContext(
            YEAR_AGO,
            TODAY,
            Map.of(A, YEAR_AGO.minusDays(1), B, TODAY.minusDays(30)),
            Map.of(A, List.of(archived, restored)),
            (id, date) -> !A.equals(id) || date.isBefore(archived) || !date.isBefore(restored));

    ReturnScopePlan plan = PeriodReturnService.plan(context, Set.of(A, B));

    assertThat(plan.start()).isEqualTo(YEAR_AGO);
    assertThat(plan.terms())
        .containsExactlyInAnyOrder(
            new ReturnTerm(A, YEAR_AGO, ReturnTerm.Kind.START),
            new ReturnTerm(A, TODAY, ReturnTerm.Kind.END),
            new ReturnTerm(A, archived, ReturnTerm.Kind.FLOW_OUT),
            new ReturnTerm(A, restored, ReturnTerm.Kind.FLOW_IN),
            new ReturnTerm(B, TODAY.minusDays(30), ReturnTerm.Kind.FLOW_IN),
            new ReturnTerm(B, TODAY, ReturnTerm.Kind.END));
  }

  @Test
  void aScopeWithNothingTrackedHasNoReturnAndNoStart() {
    ReturnScopeContext context =
        new ReturnScopeContext(YEAR_AGO, TODAY, Map.of(A, YEAR_AGO), Map.of(), (id, d) -> false);

    ReturnScopePlan plan = PeriodReturnService.plan(context, Set.of(A));

    assertThat(plan).isEqualTo(ReturnScopePlan.EMPTY);
    assertThat(
            PeriodReturnService.result(
                plan, context, Map.of(), new DatedCashFlows(List.of(), List.of())))
        .isEqualTo(PeriodReturn.NONE);
  }

  @Test
  void bookingsCountOnlyAfterTheTrackingStartAndUnknownFlowsLeaveTheReturnUnknown() {
    LocalDate start = TODAY.minusDays(10);
    ReturnScopeContext context =
        new ReturnScopeContext(start, TODAY, Map.of(A, start), Map.of(), (id, date) -> true);
    ReturnScopePlan plan = PeriodReturnService.plan(context, Set.of(A));
    Map<LocalDate, Map<UUID, AccountValuation>> values =
        Map.of(start, Map.of(A, known("1000")), TODAY, Map.of(A, known("1500")));

    // The 500 booked on the start day is already in the start value; the one after it is a flow.
    DatedCashFlows saving =
        new DatedCashFlows(
            List.of(
                new DatedCashFlows.Flow(A, start, bd("200")),
                new DatedCashFlows.Flow(A, start.plusDays(1), bd("500"))),
            List.of());
    assertThat(PeriodReturnService.result(plan, context, values, saving).percent())
        .isEqualByComparingTo("0.00");

    DatedCashFlows noRate =
        new DatedCashFlows(List.of(new DatedCashFlows.Flow(A, TODAY, null)), List.of());
    assertThat(PeriodReturnService.result(plan, context, values, noRate))
        .isEqualTo(new PeriodReturn(null, start));

    DatedCashFlows pending =
        new DatedCashFlows(List.of(), List.of(new DatedCashFlows.Flow(A, TODAY, null)));
    assertThat(PeriodReturnService.result(plan, context, values, pending).percent()).isNull();
  }

  @Test
  void anUnknownTrackedValueLeavesTheReturnUnknownButKeepsItsStart() {
    LocalDate start = TODAY.minusDays(10);
    ReturnScopeContext context =
        new ReturnScopeContext(start, TODAY, Map.of(A, start), Map.of(), (id, date) -> true);
    ReturnScopePlan plan = PeriodReturnService.plan(context, Set.of(A));

    PeriodReturn result =
        PeriodReturnService.result(
            plan,
            context,
            Map.of(start, Map.of(A, known("1000")), TODAY, Map.of(A, unknown())),
            new DatedCashFlows(List.of(), List.of()));

    assertThat(result).isEqualTo(new PeriodReturn(null, start));
  }

  // --- The whole response ------------------------------------------------------------------------

  @Test
  void accountsOnAnOpeningBalanceAreValuedInBulkAndTaxIsAFlowOnlyWithoutPositions() {
    // Methodology example 2 with B as a depot: A (a ledger) enters with 1,000 thirty days ago, the
    // depot grows from 2,000 to 2,100.
    LocalDate opening = TODAY.minusDays(30);
    Account cash = withId(ledger(), A);
    Account securities = withId(depot(), B);
    ValueHistoryStartRepository starts = mock(ValueHistoryStartRepository.class);
    AccountValuationService valuations = mock(AccountValuationService.class);
    CashFlowService cashFlows = mock(CashFlowService.class);
    when(starts.trackingStartSources(anyCollection()))
        .thenReturn(
            Map.of(
                A, new TrackingStartSources(A, opening, opening, null, null, false),
                B, new TrackingStartSources(B, null, null, YEAR_AGO.minusDays(5), null, false)));
    when(valuations.historyFromOpeningBalances(anyCollection(), eq("CHF"), anyList()))
        .thenReturn(
            Map.of(opening, Map.of(A, known(A, "1000")), TODAY, Map.of(A, known(A, "1000"))));
    when(valuations.valueAll(anyCollection(), eq("CHF"), eq(YEAR_AGO)))
        .thenReturn(List.of(known(B, "2000")));
    when(valuations.valueAll(anyCollection(), eq("CHF"), eq(TODAY)))
        .thenReturn(List.of(known(B, "2100")));
    when(cashFlows.datedFlows(anyList(), anyCollection(), any(), any(), any()))
        .thenReturn(new DatedCashFlows(List.of(), List.of()));

    PeriodReturns returns =
        new PeriodReturnService(starts, valuations, cashFlows)
            .returns(
                List.of(cash, securities),
                YEAR_AGO,
                TODAY,
                "CHF",
                Map.of(),
                Map.of(),
                (id, date) -> true,
                Map.of());

    assertThat(returns.total()).isEqualTo(new PeriodReturn(bd("4.80"), YEAR_AGO));
    verify(valuations, never())
        .valueAll(argThat(accounts -> accounts.contains(cash)), any(), any());
    verify(cashFlows)
        .datedFlows(
            argThat(ids -> Set.copyOf(ids).equals(Set.of(A, B))),
            eq(List.of(A)),
            eq(YEAR_AGO.plusDays(1)),
            eq(TODAY),
            eq("CHF"));
  }

  // --- Helpers -----------------------------------------------------------------------------------

  private static BigDecimal bd(String value) {
    return new BigDecimal(value);
  }

  private static DatedAmount flow(int daysAgo, String amount) {
    return new DatedAmount(TODAY.minusDays(daysAgo), bd(amount));
  }

  private static AccountValuation known(String value) {
    return known(A, value);
  }

  private static AccountValuation known(UUID id, String value) {
    return valuation(id, true, bd(value));
  }

  private static AccountValuation unknown() {
    return valuation(A, false, null);
  }

  private static AccountValuation valuation(UUID id, boolean known, BigDecimal value) {
    return new AccountValuation(
        id, "Account", "ASSET", "CHF", "CHF", value, null, null, false, false, known, null, null,
        false, List.of());
  }

  private static Account withId(Account account, UUID id) {
    ReflectionTestUtils.setField(account, "id", id);
    return account;
  }

  private static Account ledger() {
    Account account = new Account();
    account.setHasTransactions(true);
    return account;
  }

  private static Account card() {
    Account account = ledger();
    account.setHasStatementCycle(true);
    return account;
  }

  private static Account loan() {
    Account account = ledger();
    account.setHasAmortisation(true);
    return account;
  }

  private static Account depot() {
    Account account = ledger();
    account.setHoldsPositions(true);
    return account;
  }

  private static Account withoutLedger() {
    Account account = new Account();
    account.setHasTransactions(false);
    return account;
  }

  private static Account manualValuation() {
    Account account = withoutLedger();
    account.setManualValuation(true);
    return account;
  }
}
