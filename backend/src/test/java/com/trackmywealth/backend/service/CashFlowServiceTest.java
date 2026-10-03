package com.trackmywealth.backend.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.trackmywealth.backend.dto.AccessLevelValues;
import com.trackmywealth.backend.dto.CashFlowResponse;
import com.trackmywealth.backend.dto.CashFlowResponse.CurrencyAmount;
import com.trackmywealth.backend.dto.CurrencyConversionResult;
import com.trackmywealth.backend.entity.Account;
import com.trackmywealth.backend.repository.AccountRepository;
import com.trackmywealth.backend.repository.TransactionRepository;
import com.trackmywealth.backend.security.AuthenticatedUserPrincipal;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * US-06-05: how {@link CashFlowService} turns the per-date, per-currency query rows into figures -
 * summed per currency without a requested currency, converted day by day with one is. The queries
 * themselves are covered against PostgreSQL by the cash-flow controller tests; here they are
 * mocked, so the aggregation can be checked for cases a fixture would make long-winded.
 */
class CashFlowServiceTest {

  private static final String SOURCE = "ECB";
  private static final UUID WORKSPACE = UUID.randomUUID();
  private static final UUID MEMBER = UUID.randomUUID();
  private static final AuthenticatedUserPrincipal ACTOR =
      new AuthenticatedUserPrincipal(
          UUID.randomUUID(), "STANDARD_USER", WORKSPACE, UUID.randomUUID(), "EN");
  private static final YearMonth MONTH = YearMonth.of(2026, 9);
  private static final LocalDate DAY_1 = MONTH.atDay(1);
  private static final LocalDate DAY_2 = MONTH.atDay(2);
  private static final LocalDate DAY_3 = MONTH.atDay(3);

  private final AccessControlService accessControlService = mock(AccessControlService.class);
  private final AccountRepository accountRepository = mock(AccountRepository.class);
  private final TransactionRepository transactionRepository = mock(TransactionRepository.class);
  private final FxRateService fxRateService = mock(FxRateService.class);
  private final CashFlowService service =
      new CashFlowService(
          accessControlService, accountRepository, transactionRepository, fxRateService, SOURCE);

  @BeforeEach
  void setUp() {
    Account account = mock(Account.class);
    when(account.getId()).thenReturn(UUID.randomUUID());
    when(accessControlService.requireActingMember(ACTOR)).thenReturn(MEMBER);
    when(accountRepository.findByWorkspaceId(WORKSPACE)).thenReturn(List.of(account));
    when(accessControlService.accountsWithAccess(MEMBER, List.of(account), AccessLevelValues.READ))
        .thenReturn(List.of(account));
    // Real arithmetic, so the expected figures below can be worked out by hand.
    when(fxRateService.applyRate(any(), any()))
        .thenAnswer(
            inv ->
                inv.<BigDecimal>getArgument(0)
                    .multiply(inv.<CurrencyConversionResult>getArgument(1).rate()));
    income();
    spending();
    savingInto();
    savingOutOf();
    pending();
  }

  @Test
  void withoutACurrencyEachFigureIsSummedPerOriginalCurrency() {
    spending(row(DAY_1, "EUR", "-10"), row(DAY_2, "EUR", "-5"), row(DAY_1, "USD", "-3"));
    savingInto(row(DAY_1, "EUR", "10"));
    savingOutOf(row(DAY_2, "EUR", "10"));
    pending(row(DAY_3, "CHF", "7"));

    CashFlowResponse response = service.getCashFlow(MONTH, ACTOR, null);

    assertThat(response.spending())
        .containsExactly(
            new CurrencyAmount("EUR", new BigDecimal("15")),
            new CurrencyAmount("USD", new BigDecimal("3")));
    // Money moved in and back out nets to zero, but did move: the currency is still shown.
    assertThat(response.saving()).containsExactly(new CurrencyAmount("EUR", BigDecimal.ZERO));
    assertThat(response.pendingReview())
        .containsExactly(new CurrencyAmount("CHF", new BigDecimal("7")));
    assertThat(response.complete()).isFalse();
    verifyNoInteractions(fxRateService);
  }

  @Test
  void withACurrencyEachDayIsConvertedAtItsOwnRateAndTheMarksAreCombined() {
    income(row(DAY_1, "EUR", "100"));
    spending(row(DAY_1, "EUR", "-10"), row(DAY_2, "EUR", "-10"), row(DAY_1, "USD", "-5"));
    savingInto(row(DAY_2, "EUR", "50"));
    savingOutOf(row(DAY_3, "EUR", "20"));
    rate("EUR", DAY_1, "1.0", false, false);
    rate("EUR", DAY_2, "1.1", true, false);
    rate("EUR", DAY_3, "1.2", true, true);
    rate("USD", DAY_1, "0.9", false, false);

    CashFlowResponse response = service.getCashFlow(MONTH, ACTOR, "CHF");

    assertThat(response.income())
        .containsExactly(new CurrencyAmount("CHF", new BigDecimal("100.0"), true, false, false));
    // 10 x 1.0 + 10 x 1.1 + 5 x 0.9; day 2's rate was carried forward.
    assertThat(response.spending())
        .singleElement()
        .satisfies(
            amount -> {
              assertThat(amount.amount()).isEqualByComparingTo("25.5");
              assertThat(amount.conversionRateCarriedForward()).isTrue();
              assertThat(amount.conversionRateStale()).isFalse();
            });
    // 50 x 1.1 into saving, less 20 x 1.2 back out; day 3's rate is stale.
    assertThat(response.saving())
        .singleElement()
        .satisfies(
            amount -> {
              assertThat(amount.amount()).isEqualByComparingTo("31");
              assertThat(amount.conversionRateCarriedForward()).isTrue();
              assertThat(amount.conversionRateStale()).isTrue();
            });
    assertThat(response.pendingReview()).isEmpty();
    assertThat(response.complete()).isTrue();
    // Income and spending share day 1's EUR rate: resolved once per request, not once per figure.
    verify(fxRateService, times(1)).tryGetConversionRate("EUR", "CHF", DAY_1, SOURCE);
  }

  @Test
  void oneDayWithoutARateMakesOnlyThatFigureUnknownAndTheMonthIncomplete() {
    income(row(DAY_1, "EUR", "100"));
    spending(row(DAY_1, "EUR", "-10"), row(DAY_2, "GBP", "-10"));
    rate("EUR", DAY_1, "1.0", false, false);
    when(fxRateService.tryGetConversionRate("GBP", "CHF", DAY_2, SOURCE))
        .thenReturn(Optional.empty());

    CashFlowResponse response = service.getCashFlow(MONTH, ACTOR, "CHF");

    // Unknown, never the 10 that did convert, and never zero.
    assertThat(response.spending())
        .containsExactly(new CurrencyAmount("CHF", null, false, false, false));
    assertThat(response.income())
        .containsExactly(new CurrencyAmount("CHF", new BigDecimal("100.0"), true, false, false));
    assertThat(response.complete()).isFalse();
  }

  private void rate(
      String base, LocalDate date, String rate, boolean carriedForward, boolean stale) {
    when(fxRateService.tryGetConversionRate(base, "CHF", date, SOURCE))
        .thenReturn(
            Optional.of(
                new CurrencyConversionResult(
                    new BigDecimal(rate), base, "CHF", date, true, null, carriedForward, stale)));
  }

  private void income(Object[]... rows) {
    when(transactionRepository.sumIncomeByDateAndCurrency(anyCollection(), any(), any(), any()))
        .thenReturn(List.of(rows));
  }

  private void spending(Object[]... rows) {
    when(transactionRepository.sumSpendingByDateAndCurrency(anyCollection(), any(), any(), any()))
        .thenReturn(List.of(rows));
  }

  private void savingInto(Object[]... rows) {
    when(transactionRepository.sumSavingMovementsByDateAndCurrency(
            anyCollection(), eq(true), any(), any()))
        .thenReturn(List.of(rows));
  }

  private void savingOutOf(Object[]... rows) {
    when(transactionRepository.sumSavingMovementsByDateAndCurrency(
            anyCollection(), eq(false), any(), any()))
        .thenReturn(List.of(rows));
  }

  private void pending(Object[]... rows) {
    when(transactionRepository.sumPendingReviewByDateAndCurrency(anyCollection(), any(), any()))
        .thenReturn(List.of(rows));
  }

  private static Object[] row(LocalDate bookingDate, String currency, String amount) {
    return new Object[] {bookingDate, currency, new BigDecimal(amount)};
  }
}
