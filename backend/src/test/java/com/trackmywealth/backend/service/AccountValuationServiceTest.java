package com.trackmywealth.backend.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.trackmywealth.backend.dto.AccountValuation;
import com.trackmywealth.backend.dto.DataQualityWarningValues;
import com.trackmywealth.backend.dto.KeyedAmount;
import com.trackmywealth.backend.dto.KeyedDate;
import com.trackmywealth.backend.dto.ValueBasisValues;
import com.trackmywealth.backend.entity.Account;
import com.trackmywealth.backend.entity.AccountLoan;
import com.trackmywealth.backend.entity.AccountMortgage;
import com.trackmywealth.backend.entity.AccountSnapshot;
import com.trackmywealth.backend.entity.CustomAssetValuation;
import com.trackmywealth.backend.repository.AccountLoanRepository;
import com.trackmywealth.backend.repository.AccountMortgageRepository;
import com.trackmywealth.backend.repository.AccountSnapshotRepository;
import com.trackmywealth.backend.repository.CustomAssetValuationRepository;
import com.trackmywealth.backend.repository.TransactionRepository;
import com.trackmywealth.backend.security.AuthenticatedUserPrincipal;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.Period;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

/**
 * {@link AccountValuationService} with the repositories mocked: the value sources (US-25-04's
 * "ledger from opening balance", a ledger-less account's latest snapshot, a custom asset's
 * valuation, a loan's statement or principal, a card's ledger), the sign convention for a
 * liability, the {@code asOf} parameter, the snapshot-age staleness of #380, and that a batch reads
 * every source once, not once per account (#380). {@code OpeningBalanceControllerTest} and {@code
 * AccountListControllerTest} cover the same against PostgreSQL.
 */
class AccountValuationServiceTest {

  private static final LocalDate TODAY = LocalDate.of(2026, 10, 3);
  private static final LocalDate OPENING_DATE = LocalDate.of(2024, 10, 1);
  private static final OffsetDateTime OPENING_STATED_AT =
      OffsetDateTime.parse("2026-02-01T09:00:00Z");
  private static final UUID OPENING_ID = UUID.randomUUID();
  private static final Period STALE_AFTER = Period.ofDays(45);
  private static final AuthenticatedUserPrincipal ACTOR =
      new AuthenticatedUserPrincipal(
          UUID.randomUUID(), "STANDARD_USER", UUID.randomUUID(), UUID.randomUUID(), "EN");

  private final AccountLookupService accountLookupService = mock(AccountLookupService.class);
  private final AccessControlService accessControlService = mock(AccessControlService.class);
  private final AccountMortgageRepository mortgageRepository =
      mock(AccountMortgageRepository.class);
  private final AccountLoanRepository loanRepository = mock(AccountLoanRepository.class);
  private final CustomAssetValuationRepository valuationRepository =
      mock(CustomAssetValuationRepository.class);
  private final AccountSnapshotRepository snapshotRepository =
      mock(AccountSnapshotRepository.class);
  private final TransactionRepository transactionRepository = mock(TransactionRepository.class);
  private final AccountCurrencyService accountCurrencyService = mock(AccountCurrencyService.class);
  private final AccountDataQualityService accountDataQualityService =
      mock(AccountDataQualityService.class);
  private final FxRateService fxRateService = mock(FxRateService.class);
  private final BusinessDateService businessDateService = mock(BusinessDateService.class);
  private final AccountValuationService service = service(STALE_AFTER);

  private Account account;

  @BeforeEach
  void setUp() {
    account = account("ASSET");
    // Every account's own currency is CHF unless a test says otherwise.
    when(accountCurrencyService.ownCurrencies(anyCollection()))
        .thenAnswer(
            invocation -> {
              Map<UUID, String> currencies = new HashMap<>();
              for (Account each : invocation.<Collection<Account>>getArgument(0)) {
                currencies.put(each.getId(), "CHF");
              }
              return currencies;
            });
    // getBalance's default target currency.
    when(accountCurrencyService.ownCurrency(any())).thenReturn("CHF");
    when(accountDataQualityService.warningsForAll(anyCollection(), any())).thenReturn(Map.of());
    when(businessDateService.today()).thenReturn(TODAY);
  }

  // --- Ledger from the opening balance (US-25-04) --------------------------------------------

  @Test
  void anAssetIsWorthItsOpeningBalancePlusTheLedgerAfterIt() {
    withOpeningBalance("10000.00");
    withLedgerAfterSnapshot(OPENING_ID, TODAY, "-1234.55");

    AccountValuation valuation = service.valueIn(account, "CHF", TODAY);

    assertThat(valuation.valueKnown()).isTrue();
    assertThat(valuation.value()).isEqualByComparingTo("8765.45");
    assertThat(valuation.value().scale()).isEqualTo(4);
    assertThat(valuation.valueBasis()).isEqualTo(ValueBasisValues.LEDGER_FROM_OPENING_BALANCE);
    assertThat(ValueBasisValues.isApproximate(valuation.valueBasis())).isFalse();
  }

  @Test
  void aLiabilitySubtractsItsCashDirectionLedgerFromTheAmountOwed() {
    account = account("LIABILITY");
    account.setHasStatementCycle(true);
    withOpeningBalance("500.00");
    // Purchases are negative on the ledger and increase what is owed; a payment reduces it.
    withLedgerAfterSnapshot(OPENING_ID, TODAY, "-100.00");
    // The card's own ledger-only source is superseded, not added on top.
    withLedgerSum(account.getId(), TODAY, "-9999.00");

    AccountValuation valuation = service.valueIn(account, "CHF", TODAY);

    assertThat(valuation.value()).isEqualByComparingTo("600.00");
    assertThat(valuation.valueBasis()).isEqualTo(ValueBasisValues.LEDGER_FROM_OPENING_BALANCE);
    // ...so it is not even read.
    verify(transactionRepository, never()).sumAmountByAccountIdsAsOf(any(), any());
  }

  @Test
  void withNoRowsAfterTheOpeningDateTheValueIsTheOpeningBalance() {
    withOpeningBalance("84000.00");

    assertThat(service.valueIn(account, "CHF", TODAY).value()).isEqualByComparingTo("84000.00");
  }

  @Test
  void beforeTheOpeningDateTheValueIsUnknownNotZero() {
    withOpeningBalance("10000.00");

    AccountValuation valuation = service.valueIn(account, "CHF", OPENING_DATE.minusDays(1));

    assertThat(valuation.valueKnown()).isFalse();
    assertThat(valuation.value()).isNull();
    assertThat(valuation.valueBasis()).isNull();
  }

  @Test
  void onTheOpeningDateTheValueIsTheOpeningBalanceAlone() {
    withOpeningBalance("10000.00");

    assertThat(service.valueIn(account, "CHF", OPENING_DATE).value())
        .isEqualByComparingTo("10000.00");
  }

  @Test
  void anAccountThatHoldsPositionsStaysUnknownWithAnOpeningBalance() {
    account.setHoldsPositions(true);
    withOpeningBalance("2500.00");

    assertThat(service.valueIn(account, "CHF", TODAY).valueKnown()).isFalse();
  }

  @Test
  void anAccountWithoutAnOpeningBalanceOrOtherSourceIsUnknown() {
    AccountValuation valuation = service.valueIn(account, "CHF", TODAY);

    assertThat(valuation.valueKnown()).isFalse();
    assertThat(valuation.valueSourceStale()).isFalse();
    verify(transactionRepository, never()).sumAmountBookedAfterSnapshots(any(), any());
  }

  @Test
  void theAccountsWarningsTravelWithItsValuationKnownOrNot() {
    List<String> warnings = List.of(DataQualityWarningValues.TRANSACTIONS_BEFORE_OPENING_BALANCE);
    when(accountDataQualityService.warningsForAll(
            anyCollection(), eq(Map.of(account.getId(), OPENING_DATE))))
        .thenReturn(Map.of(account.getId(), warnings));
    withOpeningBalance("1.00");

    // Known on the date, unknown before it - the warnings are the same either way.
    assertThat(service.valueIn(account, "CHF", TODAY).warnings()).isEqualTo(warnings);
    assertThat(service.valueIn(account, "CHF", OPENING_DATE.minusDays(1)).warnings())
        .isEqualTo(warnings);
  }

  @Test
  void aSingleValuationTakesTheBatchPathWithNoPerAccountLookups() {
    withOpeningBalance("1.00");

    service.valueIn(account, "CHF", TODAY);

    verify(snapshotRepository, times(1)).findByAccountIdInAndOpeningBalanceTrue(anyCollection());
    verify(snapshotRepository, never()).findByAccountIdAndOpeningBalanceTrue(any());
    verify(accountDataQualityService, times(1)).warningsForAll(anyCollection(), any());
    verify(accountDataQualityService, never()).warningsFor(any(UUID.class));
  }

  @Test
  void aLoanOrCustomAssetIsNeverLookedUpForAnOpeningBalance() {
    account.setHasAmortisation(true);

    service.valueIn(account, "CHF", TODAY);

    verify(snapshotRepository, never()).findByAccountIdInAndOpeningBalanceTrue(any());
  }

  // --- An account without a ledger (#232 review) ---------------------------------------------

  @Test
  void anAccountWithoutALedgerIsWorthItsLatestSnapshotWithThatSnapshotsDate() {
    account.setHasTransactions(false);
    LocalDate snapshotDate = TODAY.minusDays(30);
    withLatestSnapshot(snapshot(account, UUID.randomUUID(), snapshotDate, "91000.00"));
    withOpeningBalance("84000.00");

    AccountValuation valuation = service.valueIn(account, "CHF", TODAY);

    assertThat(valuation.valueKnown()).isTrue();
    assertThat(valuation.value()).isEqualByComparingTo("91000.00");
    assertThat(valuation.valueBasis()).isEqualTo(ValueBasisValues.LATEST_SNAPSHOT);
    assertThat(valuation.valueSourceDate()).isEqualTo(snapshotDate);
    assertThat(valuation.valueSourceStale()).isFalse();
  }

  @Test
  void anAccountWithoutALedgerAndWithoutASnapshotByThenIsUnknown() {
    account.setHasTransactions(false);

    AccountValuation valuation = service.valueIn(account, "CHF", TODAY);

    assertThat(valuation.valueKnown()).isFalse();
    assertThat(valuation.valueSourceDate()).isNull();
  }

  @Test
  void anAccountWithoutALedgerThatHoldsPositionsStaysUnknown() {
    account.setHasTransactions(false);
    account.setHoldsPositions(true);
    withLatestSnapshot(snapshot(account, UUID.randomUUID(), TODAY, "1.00"));

    assertThat(service.valueIn(account, "CHF", TODAY).valueKnown()).isFalse();
    verify(snapshotRepository, never()).findLatestWithBalance(any(), any());
  }

  @Test
  void anAccountWithoutALedgerReadsNoOpeningBalanceNorALedgerAfterIt() {
    account.setHasTransactions(false);
    withLatestSnapshot(snapshot(account, UUID.randomUUID(), TODAY, "1.00"));

    service.valueIn(account, "CHF", TODAY);

    verify(snapshotRepository, never()).findByAccountIdInAndOpeningBalanceTrue(any());
    verify(transactionRepository, never()).sumAmountBookedAfterSnapshots(any(), any());
    verify(transactionRepository, never()).sumAmountByAccountIdsAsOf(any(), any());
  }

  // --- Snapshot age (#380) -----------------------------------------------------------------------

  @Test
  void aSnapshotOlderThanTheConfiguredPeriodIsStaleAndOneExactlyThatOldIsNot() {
    account.setHasTransactions(false);
    withLatestSnapshot(snapshot(account, UUID.randomUUID(), TODAY.minus(STALE_AFTER), "1.00"));
    assertThat(service.valueIn(account, "CHF", TODAY).valueSourceStale()).isFalse();

    withLatestSnapshot(
        snapshot(account, UUID.randomUUID(), TODAY.minus(STALE_AFTER).minusDays(1), "1.00"));
    AccountValuation stale = service.valueIn(account, "CHF", TODAY);
    assertThat(stale.valueSourceStale()).isTrue();
    // Still a known value, with the date that makes it stale.
    assertThat(stale.valueKnown()).isTrue();
    assertThat(stale.valueSourceDate()).isEqualTo(TODAY.minus(STALE_AFTER).minusDays(1));
  }

  @Test
  void stalenessIsJudgedAgainstTheValuationDateNotToday() {
    account.setHasTransactions(false);
    LocalDate snapshotDate = TODAY.minusDays(100);
    withLatestSnapshot(snapshot(account, UUID.randomUUID(), snapshotDate, "1.00"));

    assertThat(service.valueIn(account, "CHF", snapshotDate.plusDays(10)).valueSourceStale())
        .isFalse();
  }

  @Test
  void aValueFromTheLedgerIsNeverStaleHoweverOldItsOpeningBalance() {
    // The opening balance is two years old; the ledger after it keeps the value current.
    withOpeningBalance("100.00");

    AccountValuation valuation = service.valueIn(account, "CHF", TODAY);

    assertThat(valuation.valueSourceDate()).isNull();
    assertThat(valuation.valueSourceStale()).isFalse();
  }

  @Test
  void aNonPositiveStalenessPeriodFailsStartup() {
    assertThatThrownBy(() -> service(Period.ZERO))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("app.valuation.snapshot-stale-after");
    assertThatThrownBy(() -> service(Period.ofDays(-1)))
        .isInstanceOf(IllegalArgumentException.class);
  }

  // --- Custom assets, loans and cards ----------------------------------------------------------

  @Test
  void aCustomAssetIsWorthItsLatestValuationWhichAgesLikeASnapshot() {
    account.setManualValuation(true);
    LocalDate valuedOn = TODAY.minusMonths(6);
    CustomAssetValuation valuation = new CustomAssetValuation();
    valuation.setAccount(account);
    valuation.setValuationDate(valuedOn);
    valuation.setValue(new BigDecimal("750000.00"));
    when(valuationRepository.findLatestOnOrBefore(anyCollection(), eq(TODAY)))
        .thenReturn(List.of(valuation));

    AccountValuation value = service.valueIn(account, "CHF", TODAY);

    assertThat(value.value()).isEqualByComparingTo("750000.00");
    assertThat(value.valueBasis()).isEqualTo(ValueBasisValues.MANUAL_VALUATION);
    assertThat(value.valueSourceDate()).isEqualTo(valuedOn);
    assertThat(value.valueSourceStale()).isTrue();
  }

  @Test
  void aLoanWithAStatementOwesItsBalanceLessTheInstalmentsBookedAfterIt() {
    account = account("LIABILITY");
    account.setHasAmortisation(true);
    UUID statementId = UUID.randomUUID();
    withLatestSnapshot(snapshot(account, statementId, TODAY.minusDays(20), "200000.00"));
    withLedgerAfterSnapshot(statementId, TODAY, "1500.00");

    AccountValuation valuation = service.valueIn(account, "CHF", TODAY);

    assertThat(valuation.value()).isEqualByComparingTo("198500.00");
    assertThat(valuation.valueBasis()).isEqualTo(ValueBasisValues.LEDGER_FROM_STATEMENT_BALANCE);
    // #445: it rests on the statement, and says so.
    assertThat(valuation.valueSourceDate()).isEqualTo(TODAY.minusDays(20));
    assertThat(valuation.valueSourceStale()).isFalse();
    // With a statement, neither the principal nor the whole ledger is read.
    verify(mortgageRepository, never()).findAllById(any());
    verify(loanRepository, never()).findAllById(any());
    verify(transactionRepository, never()).sumAmountByAccountIdsAsOf(any(), any());
  }

  // --- A loan whose ledger has gone quiet (#445) ----------------------------------------------

  @Test
  void aMortgageWithAnOldStatementAndARecentInstalmentIsNotStale() {
    UUID statementId = withLoanStatement(TODAY.minusDays(400));
    withLatestLedgerDate(TODAY.minusDays(20));

    AccountValuation valuation = service.valueIn(account, "CHF", TODAY);

    assertThat(valuation.valueSourceStale()).isFalse();
    assertThat(valuation.valueSourceDate()).isEqualTo(TODAY.minusDays(400));
    verify(transactionRepository).sumAmountBookedAfterSnapshots(List.of(statementId), TODAY);
  }

  @Test
  void aMortgageWithAnOldStatementAndNothingBookedSinceIsStale() {
    withLoanStatement(TODAY.minusDays(400));
    // Its last row predates the statement: the statement is still the latest confirmation.
    withLatestLedgerDate(TODAY.minusDays(500));

    AccountValuation valuation = service.valueIn(account, "CHF", TODAY);

    assertThat(valuation.valueKnown()).isTrue();
    assertThat(valuation.valueSourceStale()).isTrue();
    assertThat(valuation.valueSourceDate()).isEqualTo(TODAY.minusDays(400));
  }

  @Test
  void aLoanWithARecentStatementAndNoLedgerRowsIsNotStale() {
    withLoanStatement(TODAY.minusDays(10));

    assertThat(service.valueIn(account, "CHF", TODAY).valueSourceStale()).isFalse();
  }

  @Test
  void aQuietLoanLedgerIsJudgedAgainstTheValuationDateAndTheConfiguredPeriod() {
    withLoanStatement(TODAY.minusDays(400));
    LocalDate lastInstalment = TODAY.minus(STALE_AFTER);
    withLatestLedgerDate(lastInstalment);
    // Exactly the configured period ago: not yet stale; a day later it is.
    assertThat(service.valueIn(account, "CHF", TODAY).valueSourceStale()).isFalse();
    when(transactionRepository.findLatestLiveBookingDates(anyCollection(), eq(TODAY.plusDays(1))))
        .thenReturn(List.of(new KeyedDate(account.getId(), lastInstalment)));
    assertThat(service.valueIn(account, "CHF", TODAY.plusDays(1)).valueSourceStale()).isTrue();
  }

  @Test
  void aLoanWithoutAStatementNeverReadsItsLatestLedgerDate() {
    account = account("LIABILITY");
    account.setHasAmortisation(true);

    AccountValuation valuation = service.valueIn(account, "CHF", TODAY);

    assertThat(valuation.valueSourceStale()).isFalse();
    verify(transactionRepository, never()).findLatestLiveBookingDates(any(), any());
  }

  @Test
  void aMortgageWithoutAStatementOwesItsPrincipalLessTheRepaymentsOnItsLedger() {
    account = account("LIABILITY");
    account.setHasAmortisation(true);
    AccountMortgage mortgage = new AccountMortgage();
    ReflectionTestUtils.setField(mortgage, "accountId", account.getId());
    mortgage.setOriginalPrincipal(new BigDecimal("500000.00"));
    when(mortgageRepository.findAllById(anyCollection())).thenReturn(List.of(mortgage));
    withLedgerSum(account.getId(), TODAY, "20000.00");

    AccountValuation valuation = service.valueIn(account, "CHF", TODAY);

    assertThat(valuation.value()).isEqualByComparingTo("480000.00");
    assertThat(valuation.valueBasis())
        .isEqualTo(ValueBasisValues.ORIGINAL_PRINCIPAL_LESS_REPAYMENTS);
    // Found as a mortgage, so the loan table is not read.
    verify(loanRepository, never()).findAllById(any());
  }

  @Test
  void aLoanWithoutRepaymentsOwesItsOriginalPrincipal() {
    account = account("LIABILITY");
    account.setHasAmortisation(true);
    AccountLoan loan = new AccountLoan();
    ReflectionTestUtils.setField(loan, "accountId", account.getId());
    loan.setOriginalPrincipal(new BigDecimal("15000.00"));
    when(loanRepository.findAllById(anyCollection())).thenReturn(List.of(loan));

    AccountValuation valuation = service.valueIn(account, "CHF", TODAY);

    assertThat(valuation.value()).isEqualByComparingTo("15000.00");
    assertThat(valuation.valueBasis()).isEqualTo(ValueBasisValues.ORIGINAL_PRINCIPAL);
    verify(loanRepository).findAllById(List.of(account.getId()));
  }

  @Test
  void aLoanWithAnUnresolvedRepaymentIsUnknownEvenWithAStatement() {
    account = account("LIABILITY");
    account.setHasAmortisation(true);
    withLatestSnapshot(snapshot(account, UUID.randomUUID(), TODAY, "1000.00"));
    when(transactionRepository.findAccountIdsWithUnresolvedLoanRepaymentAsOf(
            anyCollection(), eq(TODAY)))
        .thenReturn(List.of(account.getId()));

    assertThat(service.valueIn(account, "CHF", TODAY).valueKnown()).isFalse();
  }

  @Test
  void aCardOwesItsNegatedLedgerAndZeroWithoutOne() {
    account = account("LIABILITY");
    account.setHasStatementCycle(true);
    Account emptyCard = account("LIABILITY");
    emptyCard.setHasStatementCycle(true);
    withLedgerSum(account.getId(), TODAY, "-321.50");

    List<AccountValuation> valuations = service.valueAll(List.of(account, emptyCard), "CHF", TODAY);

    assertThat(valuations.get(0).value()).isEqualByComparingTo("321.50");
    assertThat(valuations.get(0).valueBasis()).isEqualTo(ValueBasisValues.LEDGER);
    assertThat(valuations.get(1).value()).isEqualByComparingTo("0");
    assertThat(valuations.get(1).valueBasis()).isEqualTo(ValueBasisValues.LEDGER_EMPTY);
  }

  // --- Batching (#380) -------------------------------------------------------------------------

  @Test
  void aBatchReadsEverySourceOnceWhateverTheNumberOfAccounts() {
    List<Account> accounts = new ArrayList<>();
    for (int i = 0; i < 10; i++) {
      Account cash = account("ASSET");
      Account card = account("LIABILITY");
      card.setHasStatementCycle(true);
      Account loan = account("LIABILITY");
      loan.setHasAmortisation(true);
      Account property = account("ASSET");
      property.setManualValuation(true);
      Account vested = account("ASSET");
      vested.setHasTransactions(false);
      accounts.addAll(List.of(cash, card, loan, property, vested));
    }
    AccountSnapshot opening = snapshot(accounts.get(0), OPENING_ID, OPENING_DATE, "10.00");
    opening.setOpeningBalance(true);
    when(snapshotRepository.findByAccountIdInAndOpeningBalanceTrue(anyCollection()))
        .thenReturn(List.of(opening));
    withLatestSnapshot(snapshot(accounts.get(2), UUID.randomUUID(), TODAY, "1.00"));

    List<AccountValuation> valuations = service.valueAll(accounts, "CHF", TODAY);

    assertThat(valuations).hasSize(50);
    assertThat(valuations.get(0).value()).isEqualByComparingTo("10.00");
    assertThat(valuations.get(2).value()).isEqualByComparingTo("1.00");
    verify(snapshotRepository, times(1)).findByAccountIdInAndOpeningBalanceTrue(anyCollection());
    verify(snapshotRepository, times(1)).findLatestWithBalance(anyCollection(), eq(TODAY));
    verify(valuationRepository, times(1)).findLatestOnOrBefore(anyCollection(), eq(TODAY));
    verify(transactionRepository, times(1))
        .findAccountIdsWithUnresolvedLoanRepaymentAsOf(anyCollection(), eq(TODAY));
    verify(transactionRepository, times(1)).sumAmountByAccountIdsAsOf(anyCollection(), eq(TODAY));
    verify(transactionRepository, times(1))
        .sumAmountBookedAfterSnapshots(anyCollection(), eq(TODAY));
    verify(transactionRepository, times(1))
        .findLatestLiveBookingDates(List.of(accounts.get(2).getId()), TODAY);
    verify(mortgageRepository, times(1)).findAllById(anyCollection());
    verify(loanRepository, times(1)).findAllById(anyCollection());
    verify(accountCurrencyService, times(1)).ownCurrencies(anyCollection());
    verify(accountDataQualityService, times(1)).warningsForAll(anyCollection(), any());
  }

  @Test
  void anAccountsOwnCurrencyComesFromTheBatchedLookup() {
    account = account("LIABILITY");
    account.setHasStatementCycle(true);
    when(accountCurrencyService.ownCurrencies(anyCollection()))
        .thenReturn(Map.of(account.getId(), "EUR"));

    AccountValuation valuation = service.valueIn(account, "EUR", TODAY);

    assertThat(valuation.nativeCurrency()).isEqualTo("EUR");
    assertThat(valuation.value()).isEqualByComparingTo("0");
  }

  // --- getBalance ------------------------------------------------------------------------------

  @Test
  void theBalanceIsReadAsOfTheRequestedDateOrToday() {
    when(accountLookupService.findAccountOrThrow(account.getId(), ACTOR)).thenReturn(account);
    withOpeningBalance("10000.00");
    withLedgerAfterSnapshot(OPENING_ID, TODAY, "-1000.00");
    LocalDate asOf = OPENING_DATE.plusDays(30);
    withLedgerAfterSnapshot(OPENING_ID, asOf, "-250.00");

    assertThat(service.getBalance(account.getId(), null, ACTOR, null).value())
        .isEqualByComparingTo("9000.00");
    assertThat(service.getBalance(account.getId(), asOf, ACTOR, null).value())
        .isEqualByComparingTo("9750.00");
    assertThat(service.getBalance(account.getId(), TODAY, ACTOR, null).value())
        .isEqualByComparingTo("9000.00");
    // Every read, today's or a past one, checks access to the account.
    verify(accessControlService, times(3)).requireAccountAccess(ACTOR, account);
  }

  @Test
  void aBalanceForAFutureDateIsRejected() {
    when(accountLookupService.findAccountOrThrow(account.getId(), ACTOR)).thenReturn(account);

    assertThatThrownBy(() -> service.getBalance(account.getId(), TODAY.plusDays(1), ACTOR, null))
        .isInstanceOfSatisfying(
            ResponseStatusException.class,
            e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT));
  }

  // --- Helpers ---------------------------------------------------------------------------------

  private AccountValuationService service(Period staleAfter) {
    return new AccountValuationService(
        accountLookupService,
        accessControlService,
        mortgageRepository,
        loanRepository,
        valuationRepository,
        snapshotRepository,
        transactionRepository,
        accountCurrencyService,
        accountDataQualityService,
        fxRateService,
        businessDateService,
        "ECB",
        staleAfter);
  }

  private void withOpeningBalance(String balance) {
    AccountSnapshot snapshot = snapshot(account, OPENING_ID, OPENING_DATE, balance);
    snapshot.setOpeningBalance(true);
    ReflectionTestUtils.setField(snapshot, "createdAt", OPENING_STATED_AT);
    when(snapshotRepository.findByAccountIdInAndOpeningBalanceTrue(anyCollection()))
        .thenReturn(List.of(snapshot));
  }

  // A liability with amortisation valued from a statement dated statementDate; returns its id.
  private UUID withLoanStatement(LocalDate statementDate) {
    account = account("LIABILITY");
    account.setHasAmortisation(true);
    UUID statementId = UUID.randomUUID();
    withLatestSnapshot(snapshot(account, statementId, statementDate, "1000.00"));
    return statementId;
  }

  private void withLatestLedgerDate(LocalDate bookingDate) {
    when(transactionRepository.findLatestLiveBookingDates(anyCollection(), eq(TODAY)))
        .thenReturn(List.of(new KeyedDate(account.getId(), bookingDate)));
  }

  private void withLatestSnapshot(AccountSnapshot snapshot) {
    when(snapshotRepository.findLatestWithBalance(anyCollection(), any()))
        .thenReturn(List.of(snapshot));
  }

  private void withLedgerAfterSnapshot(UUID snapshotId, LocalDate asOf, String sum) {
    when(transactionRepository.sumAmountBookedAfterSnapshots(anyCollection(), eq(asOf)))
        .thenReturn(List.of(new KeyedAmount(snapshotId, new BigDecimal(sum))));
  }

  private void withLedgerSum(UUID accountId, LocalDate asOf, String sum) {
    when(transactionRepository.sumAmountByAccountIdsAsOf(anyCollection(), eq(asOf)))
        .thenReturn(List.of(new KeyedAmount(accountId, new BigDecimal(sum))));
  }

  private static AccountSnapshot snapshot(Account owner, UUID id, LocalDate date, String balance) {
    AccountSnapshot snapshot = new AccountSnapshot();
    ReflectionTestUtils.setField(snapshot, "id", id);
    snapshot.setAccount(owner);
    snapshot.setSnapshotDate(date);
    snapshot.setBalance(new BigDecimal(balance));
    return snapshot;
  }

  // id and nature are database-generated; set here as the persisted row would carry them.
  private static Account account(String nature) {
    Account account = new Account();
    account.setName("Account");
    account.setNativeCurrency("CHF");
    ReflectionTestUtils.setField(account, "id", UUID.randomUUID());
    ReflectionTestUtils.setField(account, "nature", nature);
    return account;
  }
}
