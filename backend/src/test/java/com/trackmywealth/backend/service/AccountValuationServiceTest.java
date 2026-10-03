package com.trackmywealth.backend.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.trackmywealth.backend.dto.AccessLevelValues;
import com.trackmywealth.backend.dto.AccountValuation;
import com.trackmywealth.backend.dto.DataQualityWarningValues;
import com.trackmywealth.backend.dto.ValueBasisValues;
import com.trackmywealth.backend.entity.Account;
import com.trackmywealth.backend.entity.AccountSnapshot;
import com.trackmywealth.backend.repository.AccountLoanRepository;
import com.trackmywealth.backend.repository.AccountMortgageRepository;
import com.trackmywealth.backend.repository.AccountSnapshotRepository;
import com.trackmywealth.backend.repository.CustomAssetValuationRepository;
import com.trackmywealth.backend.repository.TransactionRepository;
import com.trackmywealth.backend.security.AuthenticatedUserPrincipal;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

/**
 * US-25-04: the "ledger from opening balance" value source of {@link AccountValuationService}, with
 * the repositories mocked - the arithmetic, the sign convention for a liability, the
 * before-the-opening-date and holding-account rules, and the {@code asOf} parameter. {@code
 * OpeningBalanceControllerTest} covers the same against PostgreSQL, through every consumer.
 */
class AccountValuationServiceTest {

  private static final LocalDate TODAY = LocalDate.of(2026, 10, 3);
  private static final LocalDate OPENING_DATE = LocalDate.of(2024, 10, 1);
  private static final AuthenticatedUserPrincipal ACTOR =
      new AuthenticatedUserPrincipal(
          UUID.randomUUID(), "STANDARD_USER", UUID.randomUUID(), UUID.randomUUID(), "EN");

  private final AccountLookupService accountLookupService = mock(AccountLookupService.class);
  private final AccessControlService accessControlService = mock(AccessControlService.class);
  private final AccountSnapshotRepository snapshotRepository =
      mock(AccountSnapshotRepository.class);
  private final TransactionRepository transactionRepository = mock(TransactionRepository.class);
  private final AccountCurrencyService accountCurrencyService = mock(AccountCurrencyService.class);
  private final AccountDataQualityService accountDataQualityService =
      mock(AccountDataQualityService.class);
  private final FxRateService fxRateService = mock(FxRateService.class);
  private final BusinessDateService businessDateService = mock(BusinessDateService.class);
  private final AccountValuationService service =
      new AccountValuationService(
          accountLookupService,
          accessControlService,
          mock(AccountMortgageRepository.class),
          mock(AccountLoanRepository.class),
          mock(CustomAssetValuationRepository.class),
          snapshotRepository,
          transactionRepository,
          accountCurrencyService,
          accountDataQualityService,
          fxRateService,
          businessDateService,
          "ECB");

  private Account account;

  @BeforeEach
  void setUp() {
    account = account("ASSET");
    when(accountCurrencyService.ownCurrency(any())).thenReturn("CHF");
    when(accountDataQualityService.warningsFor(any(Account.class), any())).thenReturn(List.of());
    when(businessDateService.today()).thenReturn(TODAY);
    when(snapshotRepository.findByAccountIdAndOpeningBalanceTrue(any()))
        .thenReturn(Optional.empty());
  }

  @Test
  void anAssetIsWorthItsOpeningBalancePlusTheLedgerAfterIt() {
    withOpeningBalance("10000.00");
    withLedgerAfterOpening(TODAY, "-1234.55");

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
    withLedgerAfterOpening(TODAY, "-100.00");

    AccountValuation valuation = service.valueIn(account, "CHF", TODAY);

    assertThat(valuation.value()).isEqualByComparingTo("600.00");
    assertThat(valuation.valueBasis()).isEqualTo(ValueBasisValues.LEDGER_FROM_OPENING_BALANCE);
    // The card's own ledger-only source is superseded, not added on top.
    verify(transactionRepository, never()).sumAmountByAccountIdAsOf(any(), any());
  }

  @Test
  void withNoRowsAfterTheOpeningDateTheValueIsTheOpeningBalance() {
    withOpeningBalance("84000.00");
    when(transactionRepository.sumAmountByAccountIdBookedAfter(
            account.getId(), OPENING_DATE, TODAY))
        .thenReturn(Optional.empty());

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
    withLedgerAfterOpening(OPENING_DATE, null);

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
    verify(transactionRepository, never()).sumAmountByAccountIdBookedAfter(any(), any(), any());
  }

  @Test
  void theAccountsWarningsTravelWithItsValuationKnownOrNot() {
    List<String> warnings = List.of(DataQualityWarningValues.TRANSACTIONS_BEFORE_OPENING_BALANCE);
    when(accountDataQualityService.warningsFor(account, OPENING_DATE)).thenReturn(warnings);
    withOpeningBalance("1.00");
    withLedgerAfterOpening(TODAY, null);

    // Known on the date, unknown before it - the warnings are the same either way.
    assertThat(service.valueIn(account, "CHF", TODAY).warnings()).isEqualTo(warnings);
    assertThat(service.valueIn(account, "CHF", OPENING_DATE.minusDays(1)).warnings())
        .isEqualTo(warnings);
  }

  @Test
  void theOpeningBalanceIsReadOnceAndAWarningNeedsNoSecondLookup() {
    withOpeningBalance("1.00");
    withLedgerAfterOpening(TODAY, null);

    service.valueIn(account, "CHF", TODAY);

    verify(snapshotRepository, times(1)).findByAccountIdAndOpeningBalanceTrue(account.getId());
    verify(accountDataQualityService).warningsFor(account, OPENING_DATE);
    verify(accountDataQualityService, never()).warningsFor(any(UUID.class));
  }

  @Test
  void aLoanOrCustomAssetIsNeverLookedUpForAnOpeningBalance() {
    account.setHasAmortisation(true);

    service.valueIn(account, "CHF", TODAY);

    verify(snapshotRepository, never()).findByAccountIdAndOpeningBalanceTrue(any());
    verify(accountDataQualityService).warningsFor(account, null);
  }

  @Test
  void anAccountWithoutALedgerIsWorthItsLatestSnapshotWithThatSnapshotsDate() {
    account.setHasTransactions(false);
    LocalDate snapshotDate = TODAY.minusMonths(3);
    AccountSnapshot latest = snapshot(snapshotDate, "91000.00");
    when(snapshotRepository
            .findFirstByAccountIdAndSnapshotDateLessThanEqualAndBalanceIsNotNullOrderBySnapshotDateDescCreatedAtDesc(
                account.getId(), TODAY))
        .thenReturn(Optional.of(latest));
    withOpeningBalance("84000.00");

    AccountValuation valuation = service.valueIn(account, "CHF", TODAY);

    assertThat(valuation.valueKnown()).isTrue();
    assertThat(valuation.value()).isEqualByComparingTo("91000.00");
    assertThat(valuation.valueBasis()).isEqualTo(ValueBasisValues.LATEST_SNAPSHOT);
    assertThat(valuation.valueSourceDate()).isEqualTo(snapshotDate);
    verify(transactionRepository, never()).sumAmountByAccountIdBookedAfter(any(), any(), any());
  }

  @Test
  void anAccountWithoutALedgerAndWithoutASnapshotByThenIsUnknown() {
    account.setHasTransactions(false);
    when(snapshotRepository
            .findFirstByAccountIdAndSnapshotDateLessThanEqualAndBalanceIsNotNullOrderBySnapshotDateDescCreatedAtDesc(
                any(), any()))
        .thenReturn(Optional.empty());

    AccountValuation valuation = service.valueIn(account, "CHF", TODAY);

    assertThat(valuation.valueKnown()).isFalse();
    assertThat(valuation.valueSourceDate()).isNull();
  }

  @Test
  void anAccountWithoutALedgerThatHoldsPositionsStaysUnknown() {
    account.setHasTransactions(false);
    account.setHoldsPositions(true);

    assertThat(service.valueIn(account, "CHF", TODAY).valueKnown()).isFalse();
    verify(snapshotRepository, never())
        .findFirstByAccountIdAndSnapshotDateLessThanEqualAndBalanceIsNotNullOrderBySnapshotDateDescCreatedAtDesc(
            any(), any());
  }

  @Test
  void theBalanceIsReadAsOfTheRequestedDateOrToday() {
    when(accountLookupService.findAccountOrThrow(account.getId(), ACTOR)).thenReturn(account);
    withOpeningBalance("10000.00");
    withLedgerAfterOpening(TODAY, "-1000.00");
    LocalDate asOf = OPENING_DATE.plusDays(30);
    withLedgerAfterOpening(asOf, "-250.00");

    assertThat(service.getBalance(account.getId(), null, ACTOR).value())
        .isEqualByComparingTo("9000.00");
    assertThat(service.getBalance(account.getId(), asOf, ACTOR).value())
        .isEqualByComparingTo("9750.00");
    verify(accessControlService, times(2))
        .requireAccountAccess(ACTOR, account, AccessLevelValues.BALANCE_ONLY);
  }

  @Test
  void aBalanceForAFutureDateIsRejected() {
    when(accountLookupService.findAccountOrThrow(account.getId(), ACTOR)).thenReturn(account);

    assertThatThrownBy(() -> service.getBalance(account.getId(), TODAY.plusDays(1), ACTOR))
        .isInstanceOfSatisfying(
            ResponseStatusException.class,
            e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT));
  }

  private void withOpeningBalance(String balance) {
    AccountSnapshot snapshot = new AccountSnapshot();
    snapshot.setAccount(account);
    snapshot.setSnapshotDate(OPENING_DATE);
    snapshot.setBalance(new BigDecimal(balance));
    snapshot.setOpeningBalance(true);
    when(snapshotRepository.findByAccountIdAndOpeningBalanceTrue(account.getId()))
        .thenReturn(Optional.of(snapshot));
  }

  private AccountSnapshot snapshot(LocalDate date, String balance) {
    AccountSnapshot snapshot = new AccountSnapshot();
    snapshot.setAccount(account);
    snapshot.setSnapshotDate(date);
    snapshot.setBalance(new BigDecimal(balance));
    return snapshot;
  }

  private void withLedgerAfterOpening(LocalDate asOf, String sum) {
    when(transactionRepository.sumAmountByAccountIdBookedAfter(account.getId(), OPENING_DATE, asOf))
        .thenReturn(Optional.ofNullable(sum).map(BigDecimal::new));
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
