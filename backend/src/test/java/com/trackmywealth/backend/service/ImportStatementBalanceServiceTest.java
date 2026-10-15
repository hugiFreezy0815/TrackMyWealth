package com.trackmywealth.backend.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.trackmywealth.backend.dto.ImportBatchValues;
import com.trackmywealth.backend.dto.ImportStatementBalanceResponse;
import com.trackmywealth.backend.dto.ImportStatementBalances;
import com.trackmywealth.backend.dto.ReconciliationResultValues;
import com.trackmywealth.backend.entity.Account;
import com.trackmywealth.backend.entity.AccountSnapshot;
import com.trackmywealth.backend.entity.ImportBatch;
import com.trackmywealth.backend.entity.ReconciliationResult;
import com.trackmywealth.backend.error.ApiErrorCode;
import com.trackmywealth.backend.error.ApiException;
import com.trackmywealth.backend.repository.AccountSnapshotRepository;
import com.trackmywealth.backend.repository.ReconciliationResultRepository;
import com.trackmywealth.backend.repository.SnapshotHoldingRepository;
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
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * US-07-12: which of a file's balances becomes the batch's statement balance, what the preview says
 * about it, and what commit and rollback do with its snapshot - without a database. The
 * Testcontainers counterpart is {@code ImportStatementBalanceControllerTest}.
 */
class ImportStatementBalanceServiceTest {

  private static final LocalDate START = LocalDate.of(2031, 1, 1);
  private static final LocalDate END = LocalDate.of(2031, 1, 31);
  private static final UUID MEMBER = UUID.randomUUID();

  private final AccountSnapshotRepository snapshots = mock(AccountSnapshotRepository.class);
  private final SnapshotHoldingRepository holdings = mock(SnapshotHoldingRepository.class);
  private final ReconciliationResultRepository results = mock(ReconciliationResultRepository.class);
  private final ReconciliationService reconciliation = mock(ReconciliationService.class);
  private final AccountCurrencyService currencies = mock(AccountCurrencyService.class);
  private final BusinessDateService businessDates = mock(BusinessDateService.class);
  private final ImportStatementBalanceService service =
      new ImportStatementBalanceService(
          snapshots,
          holdings,
          results,
          reconciliation,
          currencies,
          businessDates,
          Clock.fixed(Instant.parse("2031-02-01T08:00:00Z"), ZoneOffset.UTC));

  private final Account account = mock(Account.class);
  private ImportBatch batch;

  @BeforeEach
  void anAssetAccountInChf() {
    when(account.getId()).thenReturn(UUID.randomUUID());
    when(account.getNature()).thenReturn("ASSET");
    when(currencies.ownCurrency(account)).thenReturn("CHF");
    when(businessDates.today()).thenReturn(LocalDate.of(2031, 2, 1));
    batch = new ImportBatch();
    ReflectionTestUtils.setField(batch, "id", UUID.randomUUID());
    batch.setAccountId(account.getId());
    batch.setStatus(ImportBatchValues.PARSED);
  }

  // --- choosing the balance ---------------------------------------------------------------------

  @Test
  void theLatestBalanceInTheAccountsCurrencyClosesAndTheEarliestOpens() {
    service.record(
        batch,
        account,
        stated(
            balance(START, "263.40", "CHF"),
            balance(START, "100.00", "EUR"),
            balance(END, "1248.55", "CHF"),
            balance(END.plusDays(1), "60.00", "EUR")));

    assertThat(batch.getStatementBalanceOutcome()).isEqualTo(ImportBatchValues.BALANCE_FOUND);
    assertThat(batch.getStatementBalanceDate()).isEqualTo(END);
    assertThat(batch.getStatementBalance()).isEqualByComparingTo("1248.55");
    assertThat(batch.getStatementBalanceCurrency()).isEqualTo("CHF");
    assertThat(batch.getOpeningBalanceDate()).isEqualTo(START);
    assertThat(batch.getOpeningBalance()).isEqualByComparingTo("263.40");
  }

  @Test
  void aBalanceWithoutACurrencyIsTakenToBeInTheAccountsOwn() {
    service.record(batch, account, stated(balance(END, "-12.00", null)));

    assertThat(batch.getStatementBalance()).isEqualByComparingTo("-12.00");
    assertThat(batch.getStatementBalanceCurrency()).isEqualTo("CHF");
    assertThat(batch.getOpeningBalanceDate()).isNull();
    assertThat(batch.getOpeningBalance()).isNull();
  }

  /** A liability's snapshot holds the amount owed, positive; the file states it negative. */
  @Test
  void aLiabilitysBalanceIsStoredAsThePositiveAmountOwed() {
    when(account.getNature()).thenReturn("LIABILITY");

    service.record(
        batch, account, stated(balance(START, "-5000.00", "CHF"), balance(END, "-4800.00", "CHF")));

    assertThat(batch.getStatementBalance()).isEqualByComparingTo("4800.00");
    assertThat(batch.getOpeningBalance()).isEqualByComparingTo("5000.00");
  }

  @Test
  void twoDifferentBalancesOnTheLatestDateAreAmbiguous() {
    service.record(
        batch, account, stated(balance(END, "10.00", "CHF"), balance(END, "11.00", "CHF")));

    assertThat(batch.getStatementBalanceOutcome()).isEqualTo(ImportBatchValues.BALANCE_AMBIGUOUS);
    assertThat(batch.getStatementBalanceDate()).isNull();
    assertThat(batch.getStatementBalance()).isNull();
  }

  /** A summary page repeats the closing balance; written 10.0 or 10.00, it is one amount. */
  @Test
  void theSameBalanceStatedTwiceIsNotAmbiguous() {
    service.record(
        batch, account, stated(balance(END, "10.00", "CHF"), balance(END, "10.0", "CHF")));

    assertThat(batch.getStatementBalanceOutcome()).isEqualTo(ImportBatchValues.BALANCE_FOUND);
  }

  @Test
  void balancesOnlyInAnotherCurrencyAreNotFound() {
    service.record(batch, account, stated(balance(END, "60.00", "EUR")));

    assertThat(batch.getStatementBalanceOutcome()).isEqualTo(ImportBatchValues.BALANCE_NOT_FOUND);
  }

  @Test
  void onlyUnreadableLinesAreReportedAsSuch() {
    service.record(batch, account, new ImportStatementBalances(List.of(), 2));

    assertThat(batch.getStatementBalanceOutcome()).isEqualTo(ImportBatchValues.BALANCE_UNREADABLE);
  }

  @Test
  void aTemplateWithoutTheRuleRecordsNoOutcomeAndHasNoResponse() {
    service.record(batch, account, null);

    assertThat(batch.getStatementBalanceOutcome()).isNull();
    assertThat(service.response(batch)).isNull();
  }

  @Test
  void unreadableClosingLineDoesNotTurnOpeningIntoDocumentSnapshot() {
    service.record(
        batch, account, new ImportStatementBalances(List.of(balance(START, "100.00", "CHF")), 1));
    assertThat(batch.getStatementBalanceOutcome()).isEqualTo(ImportBatchValues.BALANCE_UNREADABLE);
    service.commit(batch, account, MEMBER);
    verify(snapshots, never()).saveAndFlush(any());
  }

  @Test
  void pdfUsesLastStatedBalanceEvenForTwoDifferentSameDayAmounts() {
    service.record(
        batch,
        account,
        new ImportStatementBalances(
            List.of(balance(END, "100.00", "CHF"), balance(END, "120.00", "CHF")), 0, true));
    assertThat(batch.getStatementBalanceOutcome()).isEqualTo(ImportBatchValues.BALANCE_FOUND);
    assertThat(batch.getStatementBalance()).isEqualByComparingTo("120.00");
    assertThat(batch.getOpeningBalanceDate()).isEqualTo(END);
    assertThat(batch.getOpeningBalance()).isEqualByComparingTo("100.00");
  }

  @Test
  void importedBalanceMustRespectAccountDateBounds() {
    found("100.00");
    when(businessDates.today()).thenReturn(END.minusDays(1));
    assertThatThrownBy(() -> service.commit(batch, account, MEMBER))
        .isInstanceOf(ApiException.class);
    when(businessDates.today()).thenReturn(END.plusDays(1));
    when(account.getOpenedAt()).thenReturn(END.plusDays(1));
    assertThatThrownBy(() -> service.commit(batch, account, MEMBER))
        .isInstanceOf(ApiException.class);
    when(account.getOpenedAt()).thenReturn(null);
    when(account.getClosedAt()).thenReturn(END.minusDays(1));
    assertThatThrownBy(() -> service.commit(batch, account, MEMBER))
        .isInstanceOf(ApiException.class);
  }

  @Test
  void historicalAcceptedDecisionWithdrawsSnapshotWithoutReopeningFinalizedHistory() {
    AccountSnapshot historical = recordedSnapshot();
    AccountSnapshot latest = new AccountSnapshot();
    ReflectionTestUtils.setField(latest, "id", UUID.randomUUID());
    when(snapshots
            .findFirstByAccountIdAndOpeningBalanceFalseAndBalanceIsNotNullAndWithdrawnAtIsNullOrderBySnapshotDateDescCreatedAtDesc(
                account.getId()))
        .thenReturn(Optional.of(latest));
    ReconciliationResult accepted = result(ReconciliationResultValues.ACCEPTED);
    when(results.findBySnapshotId(historical.getId())).thenReturn(List.of(accepted));
    assertThat(service.rollBack(batch, account, MEMBER)).isTrue();
    assertThat(historical.getWithdrawnAt()).isNotNull();
    assertThat(accepted.getStatus()).isEqualTo(ReconciliationResultValues.ACCEPTED);
    assertThat(batch.getStatementBalanceSnapshot()).isEqualTo(ImportBatchValues.SNAPSHOT_WITHDRAWN);
    verify(results, never()).deleteAll(any());
  }

  // --- preview ----------------------------------------------------------------------------------

  @Test
  void beforeTheCommitThePreviewSaysWhatItWouldDo() {
    found("100.00");
    assertThat(service.response(batch))
        .isEqualTo(
            new ImportStatementBalanceResponse(
                ImportBatchValues.BALANCE_FOUND,
                END,
                new BigDecimal("100.00"),
                "CHF",
                null,
                null,
                ImportBatchValues.SNAPSHOT_NEW));

    existingDocumentSnapshot("100.0000");
    assertThat(service.response(batch).snapshot())
        .isEqualTo(ImportBatchValues.SNAPSHOT_ALREADY_RECORDED);

    existingDocumentSnapshot("99.0000");
    assertThat(service.response(batch).snapshot())
        .isEqualTo(ImportBatchValues.SNAPSHOT_CONFLICTING);
  }

  @Test
  void afterTheCommitTheResponseSaysWhatTheCommitDid() {
    found("100.00");
    batch.setStatus(ImportBatchValues.COMMITTED);
    batch.setStatementBalanceSnapshot(ImportBatchValues.SNAPSHOT_RECORDED);

    assertThat(service.response(batch).snapshot()).isEqualTo(ImportBatchValues.SNAPSHOT_RECORDED);
    verify(snapshots, never())
        .findByAccountIdAndSnapshotDateAndSourceAndWithdrawnAtIsNull(any(), any(), any());
  }

  // --- commit -----------------------------------------------------------------------------------

  @Test
  void theCommitRecordsADocumentSnapshotOfTheBatchAndReconciles() {
    found("100.00");

    service.commit(batch, account, MEMBER);

    ArgumentCaptor<AccountSnapshot> saved = ArgumentCaptor.forClass(AccountSnapshot.class);
    verify(snapshots).saveAndFlush(saved.capture());
    assertThat(saved.getValue().getSource()).isEqualTo("DOCUMENT");
    assertThat(saved.getValue().getSnapshotDate()).isEqualTo(END);
    assertThat(saved.getValue().getBalance()).isEqualByComparingTo("100.00");
    assertThat(saved.getValue().getCurrency()).isEqualTo("CHF");
    assertThat(saved.getValue().getImportBatchId()).isEqualTo(batch.getId());
    assertThat(saved.getValue().getCreatedBy()).isEqualTo(MEMBER);
    assertThat(saved.getValue().isOpeningBalance()).isFalse();
    assertThat(batch.getStatementBalanceSnapshot()).isEqualTo(ImportBatchValues.SNAPSHOT_RECORDED);
    verify(reconciliation).reconcileLatest(account, MEMBER);
  }

  @Test
  void theCommitNeverRecordsADateThatIsImportedAlready() {
    found("100.00");
    existingDocumentSnapshot("98.00");

    service.commit(batch, account, MEMBER);

    verify(snapshots, never()).saveAndFlush(any());
    verify(reconciliation, never()).reconcileLatest(any(), any());
    assertThat(batch.getStatementBalanceSnapshot())
        .isEqualTo(ImportBatchValues.SNAPSHOT_CONFLICTING);
  }

  @Test
  void aBatchWithoutAFoundBalanceCommitsNoSnapshot() {
    service.record(batch, account, stated(balance(END, "1.00", "EUR")));

    service.commit(batch, account, MEMBER);

    verify(snapshots, never()).saveAndFlush(any());
    assertThat(batch.getStatementBalanceSnapshot()).isNull();
  }

  // --- rollback ---------------------------------------------------------------------------------

  @Test
  void aBatchWithoutASnapshotRollsBackWithoutTouchingAny() {
    when(snapshots.findByImportBatchId(batch.getId())).thenReturn(Optional.empty());

    assertThat(service.rollBack(batch, account, MEMBER)).isFalse();
    verify(snapshots, never()).delete(any());
  }

  @Test
  void anAlreadyWithdrawnSnapshotIsLeftAlone() {
    AccountSnapshot snapshot = recordedSnapshot();
    snapshot.withdraw(OffsetDateTime.now(ZoneOffset.UTC), MEMBER);

    assertThat(service.rollBack(batch, account, MEMBER)).isFalse();
    verify(snapshots, never()).findForUpdate(any(), any());
  }

  @ParameterizedTest
  @ValueSource(
      strings = {ReconciliationResultValues.ACCEPTED, ReconciliationResultValues.DISMISSED})
  void theMembersStandingDecisionRefusesTheRollbackBeforeAnythingChanges(String status) {
    AccountSnapshot snapshot = recordedSnapshot();
    ReconciliationResult decided = result(status);
    when(results.findBySnapshotId(snapshot.getId())).thenReturn(List.of(decided));

    assertThatThrownBy(() -> service.rollBack(batch, account, MEMBER))
        .isInstanceOfSatisfying(
            ApiException.class,
            e -> {
              assertThat(e.getCode())
                  .isEqualTo(ApiErrorCode.IMPORT_ROLLBACK_RECONCILIATION_DECIDED);
              assertThat(e.getBody().getProperties())
                  .containsEntry("reconciliationResultId", decided.getId());
            });
    verify(snapshots, never()).delete(any());
    verify(results, never()).deleteAll(any());
    assertThat(snapshot.getWithdrawnAt()).isNull();
  }

  /** RESOLVED is the engine's, not the member's: it never blocks a rollback. */
  @Test
  void anUndecidedSnapshotIsDeletedWithItsResults() {
    AccountSnapshot snapshot = recordedSnapshot();
    List<ReconciliationResult> undecided =
        List.of(
            result(ReconciliationResultValues.OPEN),
            result(ReconciliationResultValues.RESOLVED),
            result(ReconciliationResultValues.SUPERSEDED));
    when(results.findBySnapshotId(snapshot.getId())).thenReturn(undecided);
    when(results.existsAdjustmentOf(anyCollection())).thenReturn(false);

    assertThat(service.rollBack(batch, account, MEMBER)).isTrue();

    verify(results).deleteAll(undecided);
    verify(holdings).deleteBySnapshotId(snapshot.getId());
    verify(snapshots).delete(snapshot);
    assertThat(batch.getStatementBalanceSnapshot()).isEqualTo(ImportBatchValues.SNAPSHOT_DELETED);
  }

  @Test
  void aSnapshotWhoseResultOnceHadAnAdjustmentIsWithdrawnAndItsOpenResultSuperseded() {
    AccountSnapshot snapshot = recordedSnapshot();
    ReconciliationResult reopened = result(ReconciliationResultValues.OPEN);
    when(results.findBySnapshotId(snapshot.getId())).thenReturn(List.of(reopened));
    when(results.existsAdjustmentOf(List.of(reopened.getId()))).thenReturn(true);

    assertThat(service.rollBack(batch, account, MEMBER)).isTrue();

    assertThat(reopened.getStatus()).isEqualTo(ReconciliationResultValues.SUPERSEDED);
    assertThat(reopened.getResolvedAt()).isNotNull();
    assertThat(snapshot.getWithdrawnAt()).isNotNull();
    assertThat(snapshot.getWithdrawnBy()).isEqualTo(MEMBER);
    verify(snapshots, never()).delete(any());
    verify(results, never()).deleteAll(any());
    assertThat(batch.getStatementBalanceSnapshot()).isEqualTo(ImportBatchValues.SNAPSHOT_WITHDRAWN);
  }

  // --- helpers ----------------------------------------------------------------------------------

  private void found(String balance) {
    batch.recordStatementBalance(
        ImportBatchValues.BALANCE_FOUND, END, new BigDecimal(balance), "CHF", null, null);
  }

  private void existingDocumentSnapshot(String balance) {
    AccountSnapshot existing = new AccountSnapshot();
    existing.setBalance(new BigDecimal(balance));
    when(snapshots.findByAccountIdAndSnapshotDateAndSourceAndWithdrawnAtIsNull(
            account.getId(), END, "DOCUMENT"))
        .thenReturn(Optional.of(existing));
  }

  private AccountSnapshot recordedSnapshot() {
    AccountSnapshot snapshot = new AccountSnapshot();
    ReflectionTestUtils.setField(snapshot, "id", UUID.randomUUID());
    snapshot.setImportBatchId(batch.getId());
    when(snapshots.findByImportBatchId(batch.getId())).thenReturn(Optional.of(snapshot));
    when(snapshots.findForUpdate(snapshot.getId(), account.getId()))
        .thenReturn(Optional.of(snapshot));
    return snapshot;
  }

  private static ReconciliationResult result(String status) {
    ReconciliationResult result = new ReconciliationResult();
    ReflectionTestUtils.setField(result, "id", UUID.randomUUID());
    result.setStatus(status);
    return result;
  }

  private static ImportStatementBalances stated(ImportStatementBalances.Stated... balances) {
    return new ImportStatementBalances(List.of(balances), 0);
  }

  private static ImportStatementBalances.Stated balance(
      LocalDate date, String amount, String currency) {
    return new ImportStatementBalances.Stated(date, new BigDecimal(amount), currency);
  }
}
