package com.trackmywealth.backend.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.trackmywealth.backend.dto.AccessLevelValues;
import com.trackmywealth.backend.dto.OpeningBalanceRequest;
import com.trackmywealth.backend.dto.OpeningBalanceResponse;
import com.trackmywealth.backend.entity.Account;
import com.trackmywealth.backend.entity.AccountSnapshot;
import com.trackmywealth.backend.error.ApiErrorCode;
import com.trackmywealth.backend.error.ApiException;
import com.trackmywealth.backend.error.ExistingResourceConflictException;
import com.trackmywealth.backend.repository.AccountSnapshotRepository;
import com.trackmywealth.backend.repository.TransactionRepository;
import com.trackmywealth.backend.security.AuthenticatedUserPrincipal;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

/**
 * US-25-04: the rules {@link OpeningBalanceService} applies before it writes, with the repositories
 * mocked - applicability by capability flag, currency, date, the same-date and second-opening
 * conflicts, the earlier-transactions guard and its acknowledgement, and If-Match. {@code
 * OpeningBalanceControllerTest} covers the same flows against PostgreSQL.
 */
class OpeningBalanceServiceTest {

  private static final LocalDate TODAY = LocalDate.of(2026, 10, 3);
  private static final LocalDate OPENING_DATE = LocalDate.of(2024, 10, 1);
  private static final AuthenticatedUserPrincipal ACTOR =
      new AuthenticatedUserPrincipal(
          UUID.randomUUID(), "STANDARD_USER", UUID.randomUUID(), UUID.randomUUID(), "EN");

  private final AccountLookupService accountLookupService = mock(AccountLookupService.class);
  private final AccessControlService accessControlService = mock(AccessControlService.class);
  private final BusinessDateService businessDateService = mock(BusinessDateService.class);
  private final AccountSnapshotRepository snapshotRepository =
      mock(AccountSnapshotRepository.class);
  private final TransactionRepository transactionRepository = mock(TransactionRepository.class);
  private final AccountCurrencyService accountCurrencyService = mock(AccountCurrencyService.class);
  private final AccountDataQualityService accountDataQualityService =
      mock(AccountDataQualityService.class);
  private final OpeningBalanceService service =
      new OpeningBalanceService(
          accountLookupService,
          accessControlService,
          businessDateService,
          snapshotRepository,
          transactionRepository,
          accountCurrencyService,
          accountDataQualityService,
          new VersionPreconditionService(),
          mock(ReconciliationService.class),
          Clock.fixed(Instant.parse("2026-10-03T10:00:00Z"), ZoneOffset.UTC));

  private Account account;

  @BeforeEach
  void setUp() {
    account = new Account();
    ReflectionTestUtils.setField(account, "id", UUID.randomUUID());
    when(accountLookupService.findAccountOrThrow(account.getId(), ACTOR)).thenReturn(account);
    when(accountCurrencyService.ownCurrency(account)).thenReturn("CHF");
    when(accountDataQualityService.warningsFor(any())).thenReturn(List.of());
    when(businessDateService.today()).thenReturn(TODAY);
    when(snapshotRepository.findByAccountIdAndOpeningBalanceTrue(any()))
        .thenReturn(Optional.empty());
    when(snapshotRepository.findByAccountIdAndSnapshotDateAndSource(any(), any(), any()))
        .thenReturn(Optional.empty());
    when(transactionRepository.findEarliestLiveBookingDate(any())).thenReturn(Optional.empty());
    when(snapshotRepository.saveAndFlush(any(AccountSnapshot.class)))
        .thenAnswer(
            invocation -> {
              AccountSnapshot saved = invocation.getArgument(0);
              if (saved.getId() == null) {
                ReflectionTestUtils.setField(saved, "id", UUID.randomUUID());
              }
              ReflectionTestUtils.setField(
                  saved, "version", saved.getVersion() == null ? 0 : saved.getVersion() + 1);
              return saved;
            });
  }

  @Test
  void recordsAManualBalanceOnlyOpeningSnapshotInTheAccountsCurrency() {
    OpeningBalanceResponse response = service.record(account.getId(), request("10000.5"), ACTOR);

    assertThat(response.accountId()).isEqualTo(account.getId());
    assertThat(response.date()).isEqualTo(OPENING_DATE);
    assertThat(response.balance()).isEqualByComparingTo("10000.5");
    assertThat(response.balance().scale()).isEqualTo(4);
    assertThat(response.currency()).isEqualTo("CHF");
    assertThat(response.version()).isZero();
    verify(accessControlService).requireAccountAccess(ACTOR, account, AccessLevelValues.EDIT);
    verify(snapshotRepository)
        .saveAndFlush(
            argThat(
                snapshot ->
                    snapshot.isOpeningBalance()
                        && "MANUAL".equals(snapshot.getSource())
                        && ACTOR.userId().equals(snapshot.getCreatedBy())));
  }

  @Test
  void aLoanOrAManuallyValuedAssetTakesNone() {
    account.setHasAmortisation(true);
    assertNotApplicable();

    account.setHasAmortisation(false);
    account.setManualValuation(true);
    assertNotApplicable();
  }

  @Test
  void theCurrencyMustBeTheAccountsOwn() {
    when(accountCurrencyService.ownCurrency(account)).thenReturn("EUR");

    assertUnprocessable(request("1.00"));
  }

  @Test
  void theDateIsNeitherInTheFutureNorOutsideTheAccountsLifetime() {
    assertUnprocessable(new OpeningBalanceRequest(TODAY.plusDays(1), BigDecimal.ONE, "CHF", null));

    account.setOpenedAt(OPENING_DATE.plusDays(1));
    assertUnprocessable(request("1.00"));

    account.setOpenedAt(null);
    account.setClosedAt(OPENING_DATE.minusDays(1));
    assertUnprocessable(request("1.00"));
  }

  @Test
  void aSecondOpeningBalanceNamesTheFirst() {
    AccountSnapshot existing = openingBalance(3);
    when(snapshotRepository.findByAccountIdAndOpeningBalanceTrue(account.getId()))
        .thenReturn(Optional.of(existing));

    assertThatThrownBy(() -> service.record(account.getId(), request("1.00"), ACTOR))
        .isInstanceOfSatisfying(
            ExistingResourceConflictException.class,
            e -> {
              assertThat(e.getPropertyName()).isEqualTo("existingOpeningBalanceId");
              assertThat(e.getExistingId()).isEqualTo(existing.getId());
            });
  }

  @Test
  void earlierTransactionsAreAConflictUnlessAcknowledged() {
    LocalDate earliest = OPENING_DATE.minusDays(10);
    when(transactionRepository.findEarliestLiveBookingDate(account.getId()))
        .thenReturn(Optional.of(earliest));
    when(transactionRepository.countLiveBookedBefore(account.getId(), OPENING_DATE)).thenReturn(3L);

    assertThatThrownBy(() -> service.record(account.getId(), request("1.00"), ACTOR))
        .isInstanceOfSatisfying(
            ApiException.class,
            e -> {
              assertThat(e.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
              assertThat(e.getCode())
                  .isEqualTo(ApiErrorCode.OPENING_BALANCE_AFTER_FIRST_TRANSACTION);
              assertThat(e.getBody().getProperties())
                  .containsEntry("transactionCount", 3L)
                  .containsEntry("earliestBookingDate", earliest.toString());
            });
    verify(snapshotRepository, never()).saveAndFlush(any());

    service.record(
        account.getId(),
        new OpeningBalanceRequest(OPENING_DATE, BigDecimal.ONE, "CHF", true),
        ACTOR);
    verify(snapshotRepository).saveAndFlush(any());
  }

  @Test
  void replacingChecksEarlierTransactionsAgainUnlessAcknowledged() {
    AccountSnapshot existing = openingBalance(3);
    when(snapshotRepository.findByAccountIdAndOpeningBalanceTrue(account.getId()))
        .thenReturn(Optional.of(existing));
    when(transactionRepository.findEarliestLiveBookingDate(account.getId()))
        .thenReturn(Optional.of(OPENING_DATE.minusDays(1)));
    when(transactionRepository.countLiveBookedBefore(account.getId(), OPENING_DATE)).thenReturn(1L);

    assertThatThrownBy(() -> service.replace(account.getId(), request("2.00"), 3, ACTOR))
        .isInstanceOfSatisfying(
            ApiException.class,
            e ->
                assertThat(e.getCode())
                    .isEqualTo(ApiErrorCode.OPENING_BALANCE_AFTER_FIRST_TRANSACTION));
    verify(snapshotRepository, never()).saveAndFlush(any());
    assertThat(existing.getBalance()).isEqualByComparingTo("1.00");

    service.replace(
        account.getId(),
        new OpeningBalanceRequest(OPENING_DATE, new BigDecimal("2.00"), "CHF", true),
        3,
        ACTOR);
    verify(snapshotRepository).saveAndFlush(existing);
  }

  @Test
  void rowsOnTheOpeningDateItselfAreNotEarlier() {
    when(transactionRepository.findEarliestLiveBookingDate(account.getId()))
        .thenReturn(Optional.of(OPENING_DATE));

    service.record(account.getId(), request("1.00"), ACTOR);

    verify(transactionRepository, never()).countLiveBookedBefore(any(), any());
  }

  @Test
  void replacingNeedsTheCurrentVersionAndMayKeepItsOwnDate() {
    AccountSnapshot existing = openingBalance(3);
    when(snapshotRepository.findByAccountIdAndOpeningBalanceTrue(account.getId()))
        .thenReturn(Optional.of(existing));
    // Its own row is the MANUAL snapshot on that date - not a conflict with itself.
    when(snapshotRepository.findByAccountIdAndSnapshotDateAndSource(
            account.getId(), OPENING_DATE, "MANUAL"))
        .thenReturn(Optional.of(existing));

    assertThatThrownBy(() -> service.replace(account.getId(), request("2.00"), null, ACTOR))
        .isInstanceOfSatisfying(
            ApiException.class,
            e -> assertThat(e.getCode()).isEqualTo(ApiErrorCode.VERSION_REQUIRED));
    assertThatThrownBy(() -> service.replace(account.getId(), request("2.00"), 2, ACTOR))
        .isInstanceOfSatisfying(
            ApiException.class,
            e -> assertThat(e.getCode()).isEqualTo(ApiErrorCode.VERSION_CONFLICT));

    OpeningBalanceResponse replaced = service.replace(account.getId(), request("2.00"), 3, ACTOR);
    assertThat(replaced.id()).isEqualTo(existing.getId());
    assertThat(replaced.balance()).isEqualByComparingTo("2.00");
    assertThat(replaced.updatedAt()).isNotNull();
    assertThat(existing.getUpdatedBy()).isEqualTo(ACTOR.userId());
  }

  @Test
  void aRegularSnapshotOnTheDateIsAConflictNamingIt() {
    AccountSnapshot regular = new AccountSnapshot();
    ReflectionTestUtils.setField(regular, "id", UUID.randomUUID());
    when(snapshotRepository.findByAccountIdAndSnapshotDateAndSource(
            account.getId(), OPENING_DATE, "MANUAL"))
        .thenReturn(Optional.of(regular));

    assertThatThrownBy(() -> service.record(account.getId(), request("1.00"), ACTOR))
        .isInstanceOfSatisfying(
            ExistingResourceConflictException.class,
            e -> assertThat(e.getExistingId()).isEqualTo(regular.getId()));
  }

  @Test
  void readingOrDeletingANonExistentOpeningBalanceIsNotFound() {
    assertThatThrownBy(() -> service.get(account.getId(), ACTOR))
        .isInstanceOfSatisfying(
            ResponseStatusException.class,
            e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
    assertThatThrownBy(() -> service.delete(account.getId(), 0, ACTOR))
        .isInstanceOfSatisfying(
            ResponseStatusException.class,
            e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
    verify(accessControlService).requireAccountAccess(ACTOR, account, AccessLevelValues.READ);
  }

  @Test
  void deletingNeedsTheCurrentVersion() {
    AccountSnapshot existing = openingBalance(1);
    when(snapshotRepository.findByAccountIdAndOpeningBalanceTrue(account.getId()))
        .thenReturn(Optional.of(existing));

    assertThatThrownBy(() -> service.delete(account.getId(), 0, ACTOR))
        .isInstanceOf(ApiException.class);
    verify(snapshotRepository, never()).delete(any());

    service.delete(account.getId(), 1, ACTOR);
    verify(snapshotRepository).delete(existing);
  }

  private void assertNotApplicable() {
    assertThatThrownBy(() -> service.record(account.getId(), request("1.00"), ACTOR))
        .isInstanceOfSatisfying(
            ApiException.class,
            e -> {
              assertThat(e.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
              assertThat(e.getCode()).isEqualTo(ApiErrorCode.OPENING_BALANCE_NOT_APPLICABLE);
            });
  }

  private void assertUnprocessable(OpeningBalanceRequest request) {
    assertThatThrownBy(() -> service.record(account.getId(), request, ACTOR))
        .isInstanceOfSatisfying(
            ResponseStatusException.class,
            e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT));
  }

  private AccountSnapshot openingBalance(int version) {
    AccountSnapshot snapshot = new AccountSnapshot();
    ReflectionTestUtils.setField(snapshot, "id", UUID.randomUUID());
    ReflectionTestUtils.setField(snapshot, "version", version);
    snapshot.setAccount(account);
    snapshot.setSnapshotDate(OPENING_DATE);
    snapshot.setBalance(new BigDecimal("1.0000"));
    snapshot.setCurrency("CHF");
    snapshot.setSource("MANUAL");
    snapshot.setOpeningBalance(true);
    return snapshot;
  }

  private static OpeningBalanceRequest request(String balance) {
    return new OpeningBalanceRequest(OPENING_DATE, new BigDecimal(balance), "CHF", null);
  }
}
