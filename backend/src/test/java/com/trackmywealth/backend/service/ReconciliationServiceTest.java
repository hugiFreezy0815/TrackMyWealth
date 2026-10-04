package com.trackmywealth.backend.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.trackmywealth.backend.dto.AccessLevelValues;
import com.trackmywealth.backend.dto.ReconciliationStatusValues;
import com.trackmywealth.backend.entity.Account;
import com.trackmywealth.backend.entity.AccountSnapshot;
import com.trackmywealth.backend.entity.ReconciliationResult;
import com.trackmywealth.backend.entity.Transaction;
import com.trackmywealth.backend.entity.Workspace;
import com.trackmywealth.backend.repository.AccountSnapshotRepository;
import com.trackmywealth.backend.repository.ReconciliationResultRepository;
import com.trackmywealth.backend.repository.TransactionRepository;
import com.trackmywealth.backend.security.AuthenticatedUserPrincipal;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

class ReconciliationServiceTest {

  private static final LocalDate OPENING_DATE = LocalDate.of(2026, 1, 1);
  private static final LocalDate SNAPSHOT_DATE = LocalDate.of(2026, 9, 30);
  private static final AuthenticatedUserPrincipal ACTOR =
      new AuthenticatedUserPrincipal(
          UUID.randomUUID(), "STANDARD_USER", UUID.randomUUID(), UUID.randomUUID(), "EN");

  private final AccountLookupService accountLookupService = mock(AccountLookupService.class);
  private final AccessControlService accessControlService = mock(AccessControlService.class);
  private final AccountSnapshotRepository snapshotRepository =
      mock(AccountSnapshotRepository.class);
  private final TransactionRepository transactionRepository = mock(TransactionRepository.class);
  private final ReconciliationResultRepository resultRepository =
      mock(ReconciliationResultRepository.class);
  private final ReconciliationService service =
      new ReconciliationService(
          accountLookupService,
          accessControlService,
          snapshotRepository,
          transactionRepository,
          resultRepository,
          Clock.fixed(Instant.parse("2026-10-03T12:00:00Z"), ZoneOffset.UTC));

  private Account account;
  private AccountSnapshot opening;
  private AccountSnapshot snapshot;

  @BeforeEach
  void setUp() {
    account = account();
    opening = snapshot(OPENING_DATE, "10000.00", true);
    snapshot = snapshot(SNAPSHOT_DATE, "12345.67", false);
    when(snapshotRepository
            .findFirstByAccountIdAndOpeningBalanceFalseAndBalanceIsNotNullOrderBySnapshotDateDescCreatedAtDesc(
                account.getId()))
        .thenReturn(Optional.of(snapshot));
    when(snapshotRepository.findByAccountIdAndOpeningBalanceTrue(account.getId()))
        .thenReturn(Optional.of(opening));
    when(snapshotRepository
            .findFirstByAccountIdAndOpeningBalanceFalseAndBalanceIsNotNullAndSnapshotDateLessThanOrderBySnapshotDateDescCreatedAtDesc(
                account.getId(), SNAPSHOT_DATE))
        .thenReturn(Optional.empty());
    when(resultRepository.findBySnapshotIdAndAffectedSecurityIdIsNull(snapshot.getId()))
        .thenReturn(Optional.empty());
    when(resultRepository.findByAccountIdAndStatusAndAffectedSecurityIdIsNull(
            account.getId(), "OPEN"))
        .thenReturn(List.of());
    when(transactionRepository.sumAmountByAccountIdBookedAfter(
            account.getId(), OPENING_DATE, SNAPSHOT_DATE))
        .thenReturn(Optional.of(new BigDecimal("2300.00")));
    when(accessControlService.accountAccessLevel(ACTOR, account))
        .thenReturn(AccessLevelValues.FULL);
    when(resultRepository.saveAndFlush(any(ReconciliationResult.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));
  }

  @Test
  void aDifferenceOpensAResultForSnapshotMinusLedger() {
    service.reconcileLatest(account, ACTOR.userId());

    ArgumentCaptor<ReconciliationResult> captor =
        ArgumentCaptor.forClass(ReconciliationResult.class);
    verify(resultRepository).saveAndFlush(captor.capture());
    ReconciliationResult result = captor.getValue();
    assertThat(result.getStatus()).isEqualTo("OPEN");
    assertThat(result.getDifferenceAmount()).isEqualByComparingTo("45.67");
    assertThat(result.getProbableCause()).isEqualTo("UNKNOWN");
    assertThat(result.getSnapshot()).isSameAs(snapshot);
  }

  @Test
  void exactAgreementResolvesAnExistingOpenResult() {
    ReconciliationResult existing = result("OPEN", "45.67", snapshot);
    existing.setProbableCause("UNKNOWN");
    when(resultRepository.findBySnapshotIdAndAffectedSecurityIdIsNull(snapshot.getId()))
        .thenReturn(Optional.of(existing));
    when(transactionRepository.sumAmountByAccountIdBookedAfter(
            account.getId(), OPENING_DATE, SNAPSHOT_DATE))
        .thenReturn(Optional.of(new BigDecimal("2345.67")));

    service.reconcileLatest(account, ACTOR.userId());

    assertThat(existing.getStatus()).isEqualTo("RESOLVED");
    // The history keeps what was resolved; the status alone says it no longer applies.
    assertThat(existing.getDifferenceAmount()).isEqualByComparingTo("45.67");
    assertThat(existing.getProbableCause()).isEqualTo("UNKNOWN");
    assertThat(existing.getResolvedAt()).isNotNull();
    verify(resultRepository).saveAndFlush(existing);
  }

  @Test
  void aNewerSnapshotSupersedesAnOlderOpenDifference() {
    AccountSnapshot older = snapshot(SNAPSHOT_DATE.minusDays(30), "12000.00", false);
    ReconciliationResult oldResult = result("OPEN", "10.00", older);
    when(resultRepository.findByAccountIdAndStatusAndAffectedSecurityIdIsNull(
            account.getId(), "OPEN"))
        .thenReturn(List.of(oldResult));

    service.reconcileLatest(account, ACTOR.userId());

    assertThat(oldResult.getStatus()).isEqualTo("SUPERSEDED");
    assertThat(oldResult.getResolvedAt()).isNotNull();
    verify(resultRepository).save(oldResult);
  }

  @Test
  void noOpeningBalanceMeansNotReconciliableAndCreatesNoResult() {
    when(snapshotRepository.findByAccountIdAndOpeningBalanceTrue(account.getId()))
        .thenReturn(Optional.empty());

    service.reconcileLatest(account, ACTOR.userId());
    var status = service.status(account, ACTOR);

    assertThat(status.status()).isEqualTo(ReconciliationStatusValues.NOT_RECONCILABLE);
    assertThat(status.reason()).isEqualTo(ReconciliationStatusValues.NO_OPENING_BALANCE);
    verify(resultRepository, never()).saveAndFlush(any());
  }

  @Test
  void duplicateEvidenceWinsTheBestEffortClassification() {
    when(transactionRepository.existsDuplicateEntrySignature(
            any(), any(), any(), any(BigDecimal.class)))
        .thenReturn(true);

    service.reconcileLatest(account, ACTOR.userId());

    ArgumentCaptor<ReconciliationResult> captor =
        ArgumentCaptor.forClass(ReconciliationResult.class);
    verify(resultRepository).saveAndFlush(captor.capture());
    assertThat(captor.getValue().getProbableCause()).isEqualTo("DUPLICATE_ENTRY");
    // Snapshot 45.67 above the ledger on an asset: a duplicated row of -45.67 explains it.
    verify(transactionRepository)
        .existsDuplicateEntrySignature(
            eq(account.getId()), eq(OPENING_DATE), eq(SNAPSHOT_DATE), amountOf("-45.67"));
  }

  @Test
  void aLiabilityDuplicateIsSearchedInLedgerDirection() {
    // A card: spending is negative in the ledger and raises the balance owed. One -20.00 purchase
    // was recorded twice, so the derived balance owed is 20.00 too high.
    ReflectionTestUtils.setField(account, "nature", "LIABILITY");
    snapshot.setBalance(new BigDecimal("12300.00"));
    when(transactionRepository.sumAmountByAccountIdBookedAfter(
            account.getId(), OPENING_DATE, SNAPSHOT_DATE))
        .thenReturn(Optional.of(new BigDecimal("-2320.00")));
    when(transactionRepository.existsDuplicateEntrySignature(
            eq(account.getId()), eq(OPENING_DATE), eq(SNAPSHOT_DATE), amountOf("-20.00")))
        .thenReturn(true);

    service.reconcileLatest(account, ACTOR.userId());

    ArgumentCaptor<ReconciliationResult> captor =
        ArgumentCaptor.forClass(ReconciliationResult.class);
    verify(resultRepository).saveAndFlush(captor.capture());
    assertThat(captor.getValue().getDifferenceAmount()).isEqualByComparingTo("-20.00");
    assertThat(captor.getValue().getProbableCause()).isEqualTo("DUPLICATE_ENTRY");
  }

  @Test
  void aLiabilityBalanceAboveTheLedgerIsAnUnrecordedFeeCandidate() {
    // The card statement owes 15.00 more than the ledger explains: a fee nobody recorded.
    ReflectionTestUtils.setField(account, "nature", "LIABILITY");
    snapshot.setBalance(new BigDecimal("12315.00"));
    when(transactionRepository.sumAmountByAccountIdBookedAfter(
            account.getId(), OPENING_DATE, SNAPSHOT_DATE))
        .thenReturn(Optional.of(new BigDecimal("-2300.00")));

    service.reconcileLatest(account, ACTOR.userId());

    ArgumentCaptor<ReconciliationResult> captor =
        ArgumentCaptor.forClass(ReconciliationResult.class);
    verify(resultRepository).saveAndFlush(captor.capture());
    assertThat(captor.getValue().getDifferenceAmount()).isEqualByComparingTo("15.00");
    assertThat(captor.getValue().getProbableCause()).isEqualTo("UNRECORDED_FEE");
  }

  @Test
  void reconcilingLocksTheSnapshotSoConcurrentWritesShareOneResult() {
    service.reconcileLatest(account, ACTOR.userId());

    verify(snapshotRepository).findForUpdate(snapshot.getId(), account.getId());
  }

  @Test
  void aSmallConvertedResidualIsFxRounding() {
    snapshot.setBalance(new BigDecimal("12300.04"));
    when(transactionRepository.existsDuplicateEntrySignature(
            any(), any(), any(), any(BigDecimal.class)))
        .thenReturn(false);
    when(transactionRepository.existsLiveConvertedBookedAfter(
            account.getId(), OPENING_DATE, SNAPSHOT_DATE))
        .thenReturn(true);

    service.reconcileLatest(account, ACTOR.userId());

    ArgumentCaptor<ReconciliationResult> captor =
        ArgumentCaptor.forClass(ReconciliationResult.class);
    verify(resultRepository).saveAndFlush(captor.capture());
    assertThat(captor.getValue().getProbableCause()).isEqualTo("FX_ROUNDING");
  }

  @ParameterizedTest
  @CsvSource({"CHF, 12260.00, -40.00", "USD, 12298.88, -1.12", "GBP, 12266.80, -33.20"})
  void aCentPreciseDebitUpToFiftyIsAnUnrecordedFeeInAnyCurrency(
      String currency, String balance, String difference) {
    account.setNativeCurrency(currency);
    snapshot.setCurrency(currency);
    snapshot.setBalance(new BigDecimal(balance));

    service.reconcileLatest(account, ACTOR.userId());

    ArgumentCaptor<ReconciliationResult> captor =
        ArgumentCaptor.forClass(ReconciliationResult.class);
    verify(resultRepository).saveAndFlush(captor.capture());
    assertThat(captor.getValue().getDifferenceAmount()).isEqualByComparingTo(difference);
    assertThat(captor.getValue().getProbableCause()).isEqualTo("UNRECORDED_FEE");
  }

  @ParameterizedTest
  // above the limit, a sub-cent amount, and an asset balance above the ledger (not a debit)
  @ValueSource(strings = {"12249.99", "12298.8750", "12340.00"})
  void otherDifferencesAreNotFeeCandidates(String balance) {
    snapshot.setBalance(new BigDecimal(balance));

    service.reconcileLatest(account, ACTOR.userId());

    ArgumentCaptor<ReconciliationResult> captor =
        ArgumentCaptor.forClass(ReconciliationResult.class);
    verify(resultRepository).saveAndFlush(captor.capture());
    assertThat(captor.getValue().getProbableCause()).isEqualTo("UNKNOWN");
  }

  @Test
  void balanceOnlySeesTheSignalButNotDateOrAmount() {
    ReconciliationResult existing = result("OPEN", "45.67", snapshot);
    when(resultRepository.findBySnapshotIdAndAffectedSecurityIdIsNull(snapshot.getId()))
        .thenReturn(Optional.of(existing));
    when(accessControlService.accountAccessLevel(ACTOR, account))
        .thenReturn(AccessLevelValues.BALANCE_ONLY);

    var status = service.status(account, ACTOR);

    assertThat(status.status()).isEqualTo(ReconciliationStatusValues.OPEN_DIFFERENCE);
    assertThat(status.asOf()).isNull();
    assertThat(status.openDifference()).isNull();
    assertThat(status.currency()).isNull();
  }

  @Test
  void anAcceptedDifferenceStandsWhileItsAdjustmentClosesTheGap() {
    ReconciliationResult accepted = decided("ACCEPTED", "45.67", UUID.randomUUID());
    // Opening 10000.00 + ledger 2345.67 (2300.00 + the 45.67 adjustment) = snapshot 12345.67.
    when(transactionRepository.sumAmountByAccountIdBookedAfter(
            account.getId(), OPENING_DATE, SNAPSHOT_DATE))
        .thenReturn(Optional.of(new BigDecimal("2345.67")));

    service.reconcileLatest(account, ACTOR.userId());

    assertThat(accepted.getStatus()).isEqualTo("ACCEPTED");
    verify(transactionRepository, never()).findByIdForUpdate(any());
    verify(resultRepository, never()).saveAndFlush(any());
  }

  @Test
  void bookingTheMissingRowAfterAnAcceptWithdrawsTheAdjustmentAndResolves() {
    UUID adjustmentId = UUID.randomUUID();
    ReconciliationResult accepted = decided("ACCEPTED", "45.67", adjustmentId);
    Transaction adjustment = new Transaction();
    when(transactionRepository.findByIdForUpdate(adjustmentId)).thenReturn(Optional.of(adjustment));
    // With the adjustment and the late 45.67 income the ledger is 45.67 too high; without the
    // withdrawn adjustment it agrees.
    when(transactionRepository.sumAmountByAccountIdBookedAfter(
            account.getId(), OPENING_DATE, SNAPSHOT_DATE))
        .thenReturn(Optional.of(new BigDecimal("2391.34")))
        .thenReturn(Optional.of(new BigDecimal("2345.67")));

    service.reconcileLatest(account, ACTOR.userId());

    assertThat(adjustment.getDeletedAt()).isNotNull();
    // V39: every soft delete names its actor - here the member whose write overtook the accept.
    assertThat(adjustment.getDeletedBy()).isEqualTo(ACTOR.userId());
    verify(transactionRepository).saveAndFlush(adjustment);
    assertThat(accepted.getStatus()).isEqualTo("RESOLVED");
    assertThat(accepted.getResolutionTransactionId()).isNull();
    assertThat(accepted.getResolutionNote()).isEqualTo("member note");
  }

  @Test
  void leavingCashScopeRetiresAnAcceptanceAndWithdrawsItsEntry() {
    UUID adjustmentId = UUID.randomUUID();
    ReconciliationResult accepted = decided("ACCEPTED", "45.67", adjustmentId);
    Transaction adjustment = new Transaction();
    when(transactionRepository.findByIdForUpdate(adjustmentId)).thenReturn(Optional.of(adjustment));
    // Now holding positions, its cash is only part of what the provider reports.
    account.setHoldsPositions(true);

    service.reconcileLatest(account, ACTOR.userId());

    assertThat(accepted.getStatus()).isEqualTo("SUPERSEDED");
    assertThat(accepted.getResolutionTransactionId()).isNull();
    assertThat(accepted.getResolutionNote()).isEqualTo("member note");
    assertThat(adjustment.getDeletedAt()).isNotNull();
    assertThat(adjustment.getDeletedBy()).isEqualTo(ACTOR.userId());
    verify(snapshotRepository).findForUpdate(snapshot.getId(), account.getId());
  }

  @Test
  void anAcceptOvertakenByAnotherChangeReopensWithTheRealDifference() {
    UUID adjustmentId = UUID.randomUUID();
    ReconciliationResult accepted = decided("ACCEPTED", "45.67", adjustmentId);
    when(transactionRepository.findByIdForUpdate(adjustmentId))
        .thenReturn(Optional.of(new Transaction()));
    // An unrelated 5.00 expense after the accept: without the adjustment, 50.67 is missing.
    when(transactionRepository.sumAmountByAccountIdBookedAfter(
            account.getId(), OPENING_DATE, SNAPSHOT_DATE))
        .thenReturn(Optional.of(new BigDecimal("2340.67")))
        .thenReturn(Optional.of(new BigDecimal("2295.00")));

    service.reconcileLatest(account, ACTOR.userId());

    assertThat(accepted.getStatus()).isEqualTo("OPEN");
    assertThat(accepted.getDifferenceAmount()).isEqualByComparingTo("50.67");
    assertThat(accepted.getResolutionTransactionId()).isNull();
    assertThat(accepted.getResolvedBy()).isNull();
    assertThat(accepted.getResolutionNote()).isEqualTo("member note");
    verify(resultRepository).saveAndFlush(accepted);
  }

  @Test
  void aDismissedDifferenceStandsWhileTheAmountIsTheOneDismissed() {
    ReconciliationResult dismissed = decided("DISMISSED", "45.67", null);

    service.reconcileLatest(account, ACTOR.userId());

    assertThat(dismissed.getStatus()).isEqualTo("DISMISSED");
    verify(resultRepository, never()).saveAndFlush(any());
  }

  @Test
  void aDismissedDifferenceReopensWhenTheAmountChanges() {
    ReconciliationResult dismissed = decided("DISMISSED", "45.67", null);
    when(transactionRepository.sumAmountByAccountIdBookedAfter(
            account.getId(), OPENING_DATE, SNAPSHOT_DATE))
        .thenReturn(Optional.of(new BigDecimal("2305.67")));

    service.reconcileLatest(account, ACTOR.userId());

    assertThat(dismissed.getStatus()).isEqualTo("OPEN");
    assertThat(dismissed.getDifferenceAmount()).isEqualByComparingTo("40.00");
    assertThat(dismissed.getResolvedAt()).isNull();
    assertThat(dismissed.getResolutionNote()).isEqualTo("member note");
  }

  @Test
  void aDismissedDifferenceResolvesOnAgreement() {
    ReconciliationResult dismissed = decided("DISMISSED", "45.67", null);
    when(transactionRepository.sumAmountByAccountIdBookedAfter(
            account.getId(), OPENING_DATE, SNAPSHOT_DATE))
        .thenReturn(Optional.of(new BigDecimal("2345.67")));

    service.reconcileLatest(account, ACTOR.userId());

    assertThat(dismissed.getStatus()).isEqualTo("RESOLVED");
    assertThat(dismissed.getResolvedBy()).isNull();
  }

  @Test
  void aDismissedDifferenceIsDocumentedNotOpen() {
    decided("DISMISSED", "45.67", null);

    var status = service.status(account, ACTOR);

    assertThat(status.status()).isEqualTo(ReconciliationStatusValues.DISMISSED_DIFFERENCE);
    assertThat(status.openDifference()).isEqualByComparingTo("45.67");
    assertThat(status.currency()).isEqualTo("CHF");
  }

  @Test
  void anAcceptedDifferenceShowsTheAccountReconciled() {
    decided("ACCEPTED", "45.67", UUID.randomUUID());

    var status = service.status(account, ACTOR);

    assertThat(status.status()).isEqualTo(ReconciliationStatusValues.RECONCILED);
    assertThat(status.openDifference()).isNull();
  }

  // A member decision on the newest snapshot's result, as US-25-03 leaves it.
  private ReconciliationResult decided(String status, String difference, UUID adjustmentId) {
    ReconciliationResult value = result(status, difference, snapshot);
    value.setResolutionNote("member note");
    value.setResolutionTransactionId(adjustmentId);
    value.setResolvedBy(ACTOR.userId());
    value.setResolvedAt(OffsetDateTime.now(ZoneOffset.UTC));
    when(resultRepository.findBySnapshotIdAndAffectedSecurityIdIsNull(snapshot.getId()))
        .thenReturn(Optional.of(value));
    return value;
  }

  private static BigDecimal amountOf(String expected) {
    return argThat(actual -> actual != null && actual.compareTo(new BigDecimal(expected)) == 0);
  }

  private Account account() {
    Account value = new Account();
    ReflectionTestUtils.setField(value, "id", UUID.randomUUID());
    ReflectionTestUtils.setField(value, "nature", "ASSET");
    value.setWorkspace(new Workspace());
    value.setNativeCurrency("CHF");
    value.setHasTransactions(true);
    value.setHoldsPositions(false);
    return value;
  }

  private AccountSnapshot snapshot(LocalDate date, String balance, boolean openingBalance) {
    AccountSnapshot value = new AccountSnapshot();
    ReflectionTestUtils.setField(value, "id", UUID.randomUUID());
    value.setAccount(account);
    value.setSnapshotDate(date);
    value.setBalance(new BigDecimal(balance));
    value.setCurrency("CHF");
    value.setOpeningBalance(openingBalance);
    return value;
  }

  private static ReconciliationResult result(
      String status, String difference, AccountSnapshot snapshot) {
    ReconciliationResult value = new ReconciliationResult();
    value.setStatus(status);
    value.setDifferenceAmount(new BigDecimal(difference));
    value.setSnapshot(snapshot);
    value.setAccount(snapshot.getAccount());
    return value;
  }
}
