package com.trackmywealth.backend.service;

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
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
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
 *   <li><b>Rollback.</b> Refused while the member's decision on the snapshot's reconciliation
 *       stands ({@code ACCEPTED}, {@code DISMISSED}). Otherwise the snapshot is deleted with its
 *       results, unless one of them ever had an adjusting entry: that entry stays in the
 *       append-only ledger, pointing at the result, so the snapshot is withdrawn instead - kept as
 *       history and ignored by every balance lookup.
 * </ul>
 *
 * <p>Every method runs inside the caller's transaction, after the account's import lock and the
 * batch row's lock: two commits or rollbacks of one account never interleave here.
 */
@Service
public class ImportStatementBalanceService {

  private static final String SOURCE_DOCUMENT = "DOCUMENT";
  private static final String LIABILITY = "LIABILITY";
  private static final int ONE_BALANCE = 1;
  private static final Set<String> DECIDED =
      Set.of(ReconciliationResultValues.ACCEPTED, ReconciliationResultValues.DISMISSED);

  private final AccountSnapshotRepository snapshotRepository;
  private final SnapshotHoldingRepository holdingRepository;
  private final ReconciliationResultRepository resultRepository;
  private final ReconciliationService reconciliationService;
  private final AccountCurrencyService accountCurrencyService;
  private final Clock clock;
  private final BusinessDateService businessDates;

  public ImportStatementBalanceService(
      AccountSnapshotRepository snapshotRepository,
      SnapshotHoldingRepository holdingRepository,
      ReconciliationResultRepository resultRepository,
      ReconciliationService reconciliationService,
      AccountCurrencyService accountCurrencyService,
      BusinessDateService businessDates,
      Clock clock) {
    this.snapshotRepository = snapshotRepository;
    this.holdingRepository = holdingRepository;
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
    List<ImportStatementBalances.Stated> inCurrency =
        stated.balances().stream()
            .filter(balance -> balance.currency() == null || balance.currency().equals(currency))
            .toList();
    // An unreadable matching line could be the actual closing observation. Never silently
    // promote a readable opening or intermediate balance to the statement's closing snapshot.
    if (stated.unreadable() > 0) {
      batch.recordStatementBalance(ImportBatchValues.BALANCE_UNREADABLE, null, null, null, null, null);
      return;
    }
    if (inCurrency.isEmpty()) {
      String outcome =
          stated.unreadable() > 0
              ? ImportBatchValues.BALANCE_UNREADABLE
              : ImportBatchValues.BALANCE_NOT_FOUND;
      batch.recordStatementBalance(outcome, null, null, null, null, null);
      return;
    }
    LocalDate closingDate =
        inCurrency.stream()
            .map(ImportStatementBalances.Stated::date)
            .max(Comparator.naturalOrder())
            .orElseThrow();
    List<BigDecimal> closing = amountsOn(inCurrency, closingDate);
    // PDF balance markers are observations in statement order. A bank may print its opening
    // and closing balance on the same date; the last marker is authoritative there.
    if (!stated.pdf() && closing.size() > ONE_BALANCE) {
      batch.recordStatementBalance(
          ImportBatchValues.BALANCE_AMBIGUOUS, null, null, null, null, null);
      return;
    }
    BigDecimal closingAmount = stated.pdf()
        ? inCurrency.get(inCurrency.size() - 1).amount()
        : closing.get(0);
    if (stated.pdf()) {
      closingDate = inCurrency.get(inCurrency.size() - 1).date();
    }
    // The earliest balance before the closing date, the first the file states on that date.
    final LocalDate chosenClosingDate = closingDate;
    Optional<ImportStatementBalances.Stated> opening =
        stated.pdf()
            ? inCurrency.subList(0, inCurrency.size() - 1).stream().findFirst()
            : inCurrency.stream()
                .filter(balance -> balance.date().isBefore(chosenClosingDate))
                .min(Comparator.comparing(ImportStatementBalances.Stated::date));
    boolean liability = LIABILITY.equals(account.getNature());
    batch.recordStatementBalance(
        ImportBatchValues.BALANCE_FOUND,
        closingDate,
        snapshotSign(closingAmount, liability),
        currency,
        opening.map(ImportStatementBalances.Stated::date).orElse(null),
        opening.map(balance -> snapshotSign(balance.amount(), liability)).orElse(null));
  }

  /**
   * The batch's statement balance for its summary; {@code null} for a template without a statement
   * balance rule. Before the commit, {@code snapshot} says what a commit would do now.
   */
  public ImportStatementBalanceResponse response(ImportBatch batch) {
    String outcome = batch.getStatementBalanceOutcome();
    if (outcome == null) {
      return null;
    }
    String snapshot = batch.getStatementBalanceSnapshot();
    if (snapshot == null
        && ImportBatchValues.BALANCE_FOUND.equals(outcome)
        && ImportBatchValues.PARSED.equals(batch.getStatus())) {
      snapshot = existingOutcome(batch).orElse(ImportBatchValues.SNAPSHOT_NEW);
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
   * the account against it.
   */
  public void commit(ImportBatch batch, Account account, UUID committedBy) {
    if (!ImportBatchValues.BALANCE_FOUND.equals(batch.getStatementBalanceOutcome())) {
      return;
    }
    LocalDate date = batch.getStatementBalanceDate();
    if (date.isAfter(businessDates.today())
        || (account.getOpenedAt() != null && date.isBefore(account.getOpenedAt()))
        || (account.getClosedAt() != null && date.isAfter(account.getClosedAt()))) {
      throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, ApiErrorCode.UNPROCESSABLE,
          "The statement balance date is outside the account's permitted snapshot dates.");
    }
    Optional<String> existing = existingOutcome(batch);
    if (existing.isPresent()) {
      batch.setStatementBalanceSnapshot(existing.get());
      return;
    }
    AccountSnapshot snapshot = new AccountSnapshot();
    snapshot.setWorkspace(account.getWorkspace());
    snapshot.setAccount(account);
    snapshot.setSnapshotDate(batch.getStatementBalanceDate());
    snapshot.setBalance(batch.getStatementBalance());
    snapshot.setCurrency(batch.getStatementBalanceCurrency());
    snapshot.setSource(SOURCE_DOCUMENT);
    snapshot.setImportBatchId(batch.getId());
    snapshot.setCreatedBy(committedBy);
    snapshotRepository.saveAndFlush(snapshot);
    batch.setStatementBalanceSnapshot(ImportBatchValues.SNAPSHOT_RECORDED);
    reconciliationService.reconcileLatest(account, committedBy);
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
            .findByImportBatchId(batch.getId())
            .filter(snapshot -> snapshot.getWithdrawnAt() == null);
    if (recorded.isEmpty()) {
      return false;
    }
    // The lock a reconciliation and a member's decision take first (US-25-03): no decision lands
    // between this check and the removal.
    AccountSnapshot snapshot =
        snapshotRepository.findForUpdate(recorded.get().getId(), account.getId()).orElseThrow();
    List<ReconciliationResult> results = resultRepository.findBySnapshotId(snapshot.getId());
    boolean historical = snapshotRepository
        .findFirstByAccountIdAndOpeningBalanceFalseAndBalanceIsNotNullAndWithdrawnAtIsNullOrderBySnapshotDateDescCreatedAtDesc(account.getId())
        .filter(latest -> !latest.getId().equals(snapshot.getId())).isPresent();
    for (ReconciliationResult result : results) {
      if (!historical && DECIDED.contains(result.getStatus())) {
        ApiException exception =
            new ApiException(
                HttpStatus.CONFLICT,
                ApiErrorCode.IMPORT_ROLLBACK_RECONCILIATION_DECIDED,
                "The reconciliation of this statement's balance was "
                    + result.getStatus().toLowerCase(Locale.ROOT)
                    + "; reopen it before rolling back the import.");
        exception.getBody().setProperty("reconciliationResultId", result.getId());
        throw exception;
      }
    }
    List<UUID> resultIds = results.stream().map(ReconciliationResult::getId).toList();
    if (historical && results.stream().anyMatch(result -> DECIDED.contains(result.getStatus()))) {
      // A finalized decision cannot be reopened by the member. Preserve its audit trail and
      // adjustment as history; retire the source observation without deleting its references.
      snapshot.withdraw(OffsetDateTime.now(clock), rolledBackBy);
      snapshotRepository.saveAndFlush(snapshot);
      batch.setStatementBalanceSnapshot(ImportBatchValues.SNAPSHOT_WITHDRAWN);
    } else if (!resultIds.isEmpty() && resultRepository.existsAdjustmentOf(resultIds)) {
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
      batch.setStatementBalanceSnapshot(ImportBatchValues.SNAPSHOT_WITHDRAWN);
    } else {
      // Removed through the session, not in bulk: the results are loaded and point at the
      // snapshot, so a bulk delete would leave them behind to be flushed against a deleted row.
      resultRepository.deleteAll(results);
      resultRepository.flush();
      holdingRepository.deleteBySnapshotId(snapshot.getId());
      snapshotRepository.delete(snapshot);
      snapshotRepository.flush();
      batch.setStatementBalanceSnapshot(ImportBatchValues.SNAPSHOT_DELETED);
    }
    return true;
  }

  // ALREADY_RECORDED or CONFLICTING when an imported snapshot of the balance's date is in effect.
  private Optional<String> existingOutcome(ImportBatch batch) {
    return snapshotRepository
        .findByAccountIdAndSnapshotDateAndSourceAndWithdrawnAtIsNull(
            batch.getAccountId(), batch.getStatementBalanceDate(), SOURCE_DOCUMENT)
        .map(
            existing ->
                existing.getBalance() != null
                        && existing.getBalance().compareTo(batch.getStatementBalance()) == 0
                    ? ImportBatchValues.SNAPSHOT_ALREADY_RECORDED
                    : ImportBatchValues.SNAPSHOT_CONFLICTING);
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
