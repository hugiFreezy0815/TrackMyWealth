package com.trackmywealth.backend.service;

import com.trackmywealth.backend.dto.ImportBatchValues;
import com.trackmywealth.backend.dto.ImportSnapshotPlan;
import com.trackmywealth.backend.dto.ImportSnapshotValues;
import com.trackmywealth.backend.dto.ImportStatementBalanceResponse;
import com.trackmywealth.backend.dto.ImportStatementBalances;
import com.trackmywealth.backend.dto.ReconciliationResultValues;
import com.trackmywealth.backend.entity.Account;
import com.trackmywealth.backend.entity.AccountSnapshot;
import com.trackmywealth.backend.entity.ImportBatch;
import com.trackmywealth.backend.entity.ReconciliationResult;
import com.trackmywealth.backend.entity.SnapshotHolding;
import com.trackmywealth.backend.error.ApiErrorCode;
import com.trackmywealth.backend.error.ApiException;
import com.trackmywealth.backend.repository.AccountSnapshotRepository;
import com.trackmywealth.backend.repository.ImportBatchRepository;
import com.trackmywealth.backend.repository.ReconciliationResultRepository;
import com.trackmywealth.backend.repository.SnapshotHoldingRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * US-07-12 (FR-REC-002/007): a statement's balance, read from its import file, becomes an account
 * snapshot when the batch is committed, and goes again when the batch is rolled back.
 *
 * <ul>
 *   <li><b>Parse.</b> Of the balances the file states ({@link ImportStatementBalanceReaderService})
 *       only those in the account's own currency count (a card's billing currency). The latest
 *       dated one is the closing balance; two different ones on that date are {@code AMBIGUOUS}.
 *       The earliest other one is the opening balance, shown only: whether it becomes the account's
 *       opening balance is the member's call (US-25-04), never the import's. A liability's balance
 *       is stored with the snapshot's sign, the positive amount owed.
 *   <li><b>Preview and commit.</b> The closing balance becomes a {@code DOCUMENT} snapshot of its
 *       date that points at the batch, and the account is reconciled against it (US-25-02). An
 *       imported snapshot of that date that exists already is never replaced: the batch records
 *       {@code ALREADY_RECORDED} or {@code CONFLICTING}, and the preview says so beforehand.
 *   <li><b>Rollback.</b> Refused while the member's decision on the reconciliation of the account's
 *       latest balance stands ({@code ACCEPTED}, {@code DISMISSED}): it can be reopened. A decision
 *       on an older balance is final and cannot be: the snapshot is withdrawn instead, as it is
 *       when a result of it ever had an adjusting entry, which stays in the append-only ledger
 *       pointing at the result. A withdrawn snapshot is kept as history and ignored by every
 *       balance lookup. Any other snapshot is deleted with its results.
 * </ul>
 *
 * <p>A holdings statement's snapshot (US-07-13, {@link ImportHoldingsService}) is rolled back the
 * same way, its holdings with it.
 *
 * <p>Every method runs inside the caller's transaction, after the account's import lock and the
 * batch row's lock: two commits or rollbacks of one account never interleave here.
 */
@Service
public class ImportStatementBalanceService {

  private static final String SOURCE_DOCUMENT = "DOCUMENT";
  private static final String LIABILITY = "LIABILITY";
  // A date's balance is ambiguous with more than one amount (a named limit, as PMD requires).
  private static final int ONE_BALANCE = 1;
  private static final Set<String> DECIDED =
      Set.of(ReconciliationResultValues.ACCEPTED, ReconciliationResultValues.DISMISSED);

  private final AccountSnapshotRepository snapshotRepository;
  private final SnapshotHoldingRepository holdingRepository;
  private final ImportBatchRepository batchRepository;
  private final ImportSnapshotCompletionService completionService;
  private final ReconciliationResultRepository resultRepository;
  private final ReconciliationService reconciliationService;
  private final AccountCurrencyService accountCurrencyService;
  private final Clock clock;
  private final BusinessDateService businessDates;

  public ImportStatementBalanceService(
      AccountSnapshotRepository snapshotRepository,
      SnapshotHoldingRepository holdingRepository,
      ImportBatchRepository batchRepository,
      ImportSnapshotCompletionService completionService,
      ReconciliationResultRepository resultRepository,
      ReconciliationService reconciliationService,
      AccountCurrencyService accountCurrencyService,
      BusinessDateService businessDates,
      Clock clock) {
    this.snapshotRepository = snapshotRepository;
    this.holdingRepository = holdingRepository;
    this.batchRepository = batchRepository;
    this.completionService = completionService;
    this.resultRepository = resultRepository;
    this.reconciliationService = reconciliationService;
    this.accountCurrencyService = accountCurrencyService;
    this.clock = clock;
    this.businessDates = businessDates;
  }

  /**
   * Records on the batch what a parse read as the file's balance (see the class comment); {@code
   * stated} is {@code null} for a template without a statement balance rule.
   */
  public void record(ImportBatch batch, Account account, ImportStatementBalances stated) {
    if (stated == null) {
      batch.recordStatementBalance(null, null, null, null, null, null);
      return;
    }
    String currency = accountCurrencyService.ownCurrency(account);
    // A matched line that could not be read may be the actual closing balance: never promote an
    // opening or intermediate balance in its place. A line in another currency hides nothing.
    if (stated.unreadableIn(currency) > 0) {
      batch.recordStatementBalance(
          ImportBatchValues.BALANCE_UNREADABLE, null, null, null, null, null);
      return;
    }
    List<ImportStatementBalances.Stated> inCurrency =
        stated.balances().stream()
            .filter(balance -> balance.currency() == null || balance.currency().equals(currency))
            .toList();
    if (inCurrency.isEmpty()) {
      batch.recordStatementBalance(
          ImportBatchValues.BALANCE_NOT_FOUND, null, null, null, null, null);
      return;
    }
    ImportStatementBalances.Stated closing;
    Optional<ImportStatementBalances.Stated> opening;
    if (stated.fileOrdered()) {
      // A PDF's balance lines are observations in statement order; a bank may print its opening
      // and closing balance on the same date, so the last line is the closing one.
      closing = inCurrency.get(inCurrency.size() - 1);
      opening = inCurrency.subList(0, inCurrency.size() - 1).stream().findFirst();
    } else {
      LocalDate closingDate =
          inCurrency.stream()
              .map(ImportStatementBalances.Stated::date)
              .max(Comparator.naturalOrder())
              .orElseThrow();
      List<BigDecimal> amounts = amountsOn(inCurrency, closingDate);
      if (amounts.size() > ONE_BALANCE) {
        batch.recordStatementBalance(
            ImportBatchValues.BALANCE_AMBIGUOUS, null, null, null, null, null);
        return;
      }
      closing = new ImportStatementBalances.Stated(closingDate, amounts.get(0), currency);
      // The earliest balance before the closing date.
      opening =
          inCurrency.stream()
              .filter(balance -> balance.date().isBefore(closingDate))
              .min(Comparator.comparing(ImportStatementBalances.Stated::date));
    }
    boolean liability = LIABILITY.equals(account.getNature());
    batch.recordStatementBalance(
        withinAccountDates(account, closing.date())
            ? ImportBatchValues.BALANCE_FOUND
            : ImportBatchValues.BALANCE_OUT_OF_RANGE,
        closing.date(),
        snapshotSign(closing.amount(), liability),
        currency,
        opening.map(ImportStatementBalances.Stated::date).orElse(null),
        opening.map(balance -> snapshotSign(balance.amount(), liability)).orElse(null));
  }

  /**
   * The batch's statement balance for its summary; {@code null} for a template without a statement
   * balance rule. Before the commit, it says what a commit would do now: {@code OUT_OF_RANGE} once
   * the account's dates no longer admit the balance's, otherwise {@code snapshot} says whether it
   * is new, recorded already or conflicting.
   */
  public ImportStatementBalanceResponse response(ImportBatch batch, Account account) {
    String outcome = batch.getStatementBalanceOutcome();
    if (outcome == null) {
      return null;
    }
    String snapshot = batch.getStatementBalanceSnapshot();
    if (ImportBatchValues.BALANCE_FOUND.equals(outcome)
        && ImportBatchValues.PARSED.equals(batch.getStatus())) {
      if (withinAccountDates(account, batch.getStatementBalanceDate())) {
        snapshot = plan(batch, account).outcome();
      } else {
        outcome = ImportBatchValues.BALANCE_OUT_OF_RANGE;
      }
    }
    return new ImportStatementBalanceResponse(
        outcome,
        batch.getStatementBalanceDate(),
        batch.getStatementBalance(),
        batch.getStatementBalanceCurrency(),
        batch.getOpeningBalanceDate(),
        batch.getOpeningBalance(),
        snapshot);
  }

  /**
   * On commit, after the batch's rows are in the ledger: records the closing balance as a {@code
   * DOCUMENT} snapshot of the batch, unless an imported snapshot of its date exists, and reconciles
   * the account against it. A balance the account's dates no longer admit (they changed since the
   * parse) is recorded as {@code OUT_OF_RANGE} and skipped: it never stops the rows' commit.
   */
  public void commit(ImportBatch batch, Account account, UUID committedBy) {
    if (!ImportBatchValues.BALANCE_FOUND.equals(batch.getStatementBalanceOutcome())) {
      return;
    }
    if (!withinAccountDates(account, batch.getStatementBalanceDate())) {
      batch.markStatementBalanceOutOfRange();
      return;
    }
    ImportSnapshotPlan plan = plan(batch, account);
    if (!plan.committable()) {
      // ALREADY_RECORDED or CONFLICTING: the balance is not recorded; the rows still go in.
      batch.setStatementBalanceSnapshot(plan.outcome());
      return;
    }
    // A new snapshot, or the balance a holdings statement's snapshot of the date lacks (US-07-31).
    // The rows' own reconciliation ran before; the commit compares against the snapshot now.
    batch.setStatementBalanceSnapshot(
        completionService.commit(
            batch,
            account,
            batch.getStatementBalanceDate(),
            batch.getStatementBalanceCurrency(),
            plan,
            batch.getStatementBalance(),
            List.of(),
            committedBy));
  }

  // US-07-31: what the balance would do to the imported snapshot of its date.
  private ImportSnapshotPlan plan(ImportBatch batch, Account account) {
    return completionService.plan(
        account, batch.getStatementBalanceDate(), batch.getStatementBalance(), Map.of(), true);
  }

  /**
   * On rollback, before the batch's transactions are touched: deletes or withdraws the snapshot its
   * commit recorded (see the class comment). The caller reconciles the account once the ledger is
   * rolled back too.
   *
   * @return whether the batch had a snapshot in effect, which is now gone
   * @throws ApiException 409 {@code IMPORT_ROLLBACK_RECONCILIATION_DECIDED} while the member's
   *     decision on its reconciliation stands; nothing has been changed then
   */
  public boolean rollBack(ImportBatch batch, Account account, UUID rolledBackBy) {
    Optional<AccountSnapshot> recorded =
        snapshotRepository
            .findContributedBy(batch.getId())
            .filter(snapshot -> snapshot.getWithdrawnAt() == null);
    if (recorded.isEmpty()) {
      return false;
    }
    // The lock a reconciliation and a member's decision take first (US-25-03): no decision lands
    // between this check and the removal.
    AccountSnapshot snapshot =
        snapshotRepository.findForUpdate(recorded.get().getId(), account.getId()).orElseThrow();
    List<ReconciliationResult> results = resultRepository.findBySnapshotId(snapshot.getId());
    // A decision on the account's latest balance can be reopened, so it blocks the rollback. One
    // on an older balance is final (ReconciliationHistoryService#isFinalized): it can never be
    // reopened, so instead of blocking for ever the snapshot is withdrawn, keeping the decision as
    // history (product owner, 2026-10-15, #272).
    boolean historical =
        snapshotRepository
            .findLatestObserved(account.getId())
            .filter(newest -> !newest.getId().equals(snapshot.getId()))
            .isPresent();
    Optional<ReconciliationResult> decided =
        results.stream().filter(result -> DECIDED.contains(result.getStatus())).findFirst();
    if (!historical && decided.isPresent()) {
      ApiException exception =
          new ApiException(
              HttpStatus.CONFLICT,
              ApiErrorCode.IMPORT_ROLLBACK_RECONCILIATION_DECIDED,
              "The reconciliation of this statement's balance was "
                  + decided.get().getStatus().toLowerCase(Locale.ROOT)
                  + "; reopen it before rolling back the import.");
      exception.getBody().setProperty("reconciliationResultId", decided.get().getId());
      throw exception;
    }
    List<UUID> resultIds = results.stream().map(ReconciliationResult::getId).toList();
    boolean keptAsHistory =
        decided.isPresent()
            || !resultIds.isEmpty() && resultRepository.existsAdjustmentOf(resultIds);
    // US-07-31: the other batches whose statements completed the snapshot.
    Set<UUID> others = contributors(snapshot);
    others.remove(batch.getId());
    if (keptAsHistory) {
      // The decision covered the snapshot as a whole: it is withdrawn as a whole, for every batch
      // that contributed to it.
      withdraw(snapshot, results, rolledBackBy);
      recordRemoval(batch, ImportBatchValues.SNAPSHOT_WITHDRAWN);
      batchRepository
          .findAllById(others)
          .forEach(other -> recordRemoval(other, ImportBatchValues.SNAPSHOT_WITHDRAWN));
    } else if (others.isEmpty()) {
      // Removed through the session, not in bulk: the results are loaded and point at the
      // snapshot, so a bulk delete would leave them behind to be flushed against a deleted row.
      resultRepository.deleteAll(results);
      resultRepository.flush();
      holdingRepository.deleteBySnapshotId(snapshot.getId());
      snapshotRepository.delete(snapshot);
      snapshotRepository.flush();
      recordRemoval(batch, ImportBatchValues.SNAPSHOT_DELETED);
    } else {
      removeParts(batch, snapshot, results, others);
    }
    return true;
  }

  // US-07-31: takes out only this batch's parts of a snapshot another batch completed; the snapshot
  // stays, passing to a remaining contributor if this batch recorded it. Its results go with the
  // balance they compared, and the caller reconciles afresh.
  private void removeParts(
      ImportBatch batch,
      AccountSnapshot snapshot,
      List<ReconciliationResult> results,
      Set<UUID> others) {
    holdingRepository.deleteBySnapshotIdAndImportBatchId(snapshot.getId(), batch.getId());
    if (batch.getId().equals(snapshot.getBalanceImportBatchId())) {
      resultRepository.deleteAll(results);
      resultRepository.flush();
      snapshot.setImportedBalance(null, null);
    }
    if (batch.getId().equals(snapshot.getImportBatchId())) {
      snapshot.setImportBatchId(
          snapshot.getBalanceImportBatchId() != null
              ? snapshot.getBalanceImportBatchId()
              : others.iterator().next());
    }
    snapshotRepository.saveAndFlush(snapshot);
    recordRemoval(batch, ImportSnapshotValues.REMOVED);
  }

  // Every batch that recorded the snapshot or contributed a part of it.
  private Set<UUID> contributors(AccountSnapshot snapshot) {
    Set<UUID> batches = new LinkedHashSet<>();
    if (snapshot.getImportBatchId() != null) {
      batches.add(snapshot.getImportBatchId());
    }
    if (snapshot.getBalanceImportBatchId() != null) {
      batches.add(snapshot.getBalanceImportBatchId());
    }
    holdingRepository.findBySnapshotId(snapshot.getId()).stream()
        .map(SnapshotHolding::getImportBatchId)
        .filter(Objects::nonNull)
        .forEach(batches::add);
    return batches;
  }

  // Keeps the snapshot as history only: its results stay (a final decision, or an adjusting entry
  // that points at one), its open result is superseded, and no balance lookup sees it any more.
  private void withdraw(
      AccountSnapshot snapshot, List<ReconciliationResult> results, UUID rolledBackBy) {
    OffsetDateTime now = OffsetDateTime.now(clock);
    for (ReconciliationResult result : results) {
      if (ReconciliationResultValues.OPEN.equals(result.getStatus())) {
        result.setStatus(ReconciliationResultValues.SUPERSEDED);
        result.setResolvedAt(now);
      }
    }
    resultRepository.saveAllAndFlush(results);
    snapshot.withdraw(now, rolledBackBy);
    snapshotRepository.saveAndFlush(snapshot);
  }

  // A holdings statement's batch (#273) records its snapshot's fate in its own column.
  private static void recordRemoval(ImportBatch batch, String outcome) {
    if (batch.isHoldingsStatement()) {
      batch.setHoldingsSnapshot(outcome);
    } else {
      batch.setStatementBalanceSnapshot(outcome);
    }
  }

  // A snapshot may not lie in the future, before the account was opened or after it was closed
  // (as for a snapshot recorded by hand, AccountSnapshotService).
  private boolean withinAccountDates(Account account, LocalDate date) {
    return !date.isAfter(businessDates.today())
        && (account.getOpenedAt() == null || !date.isBefore(account.getOpenedAt()))
        && (account.getClosedAt() == null || !date.isAfter(account.getClosedAt()));
  }

  // The different amounts stated for date, in file order; 1.10 and 1.1 are one amount.
  private static List<BigDecimal> amountsOn(
      List<ImportStatementBalances.Stated> balances, LocalDate date) {
    List<BigDecimal> amounts = new ArrayList<>();
    for (ImportStatementBalances.Stated balance : balances) {
      if (balance.date().equals(date)
          && amounts.stream().noneMatch(amount -> amount.compareTo(balance.amount()) == 0)) {
        amounts.add(balance.amount());
      }
    }
    return amounts;
  }

  // The file states a balance as the account holder sees it: negative is owed. A liability's
  // snapshot holds the amount owed as a positive figure, as its ledger balance does.
  private static BigDecimal snapshotSign(BigDecimal stated, boolean liability) {
    return liability ? stated.negate() : stated;
  }
}
