package com.trackmywealth.backend.service;

import com.trackmywealth.backend.dto.AccessLevelValues;
import com.trackmywealth.backend.dto.ReconciliationResultValues;
import com.trackmywealth.backend.dto.ReconciliationStatusResponse;
import com.trackmywealth.backend.dto.ReconciliationStatusValues;
import com.trackmywealth.backend.entity.Account;
import com.trackmywealth.backend.entity.AccountSnapshot;
import com.trackmywealth.backend.entity.ReconciliationResult;
import com.trackmywealth.backend.repository.AccountSnapshotRepository;
import com.trackmywealth.backend.repository.ReconciliationResultRepository;
import com.trackmywealth.backend.repository.TransactionRepository;
import com.trackmywealth.backend.security.AuthenticatedUserPrincipal;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * US-25-02/FR-REC-002/003/005: cash reconciliation against the newest observed account snapshot.
 *
 * <p>The engine is synchronous and deterministic: source writes call {@link
 * #reconcileAfterLedgerChange} or {@link #reconcileLatest} inside their own transaction. It never
 * owns a second balance formula. The cash value is the US-25-04 opening balance plus the live
 * ledger after it, with liability sign taken from {@link Account#getNature}; a missing opening
 * balance means the account is explicitly not reconcilable, never silently assumed to start at
 * zero.
 *
 * <p>A member's decision (US-25-03, {@link ReconciliationDecisionService}) covers one exact amount.
 * An {@code ACCEPTED} result stays while its adjusting entry makes the account agree; once it no
 * longer does, the engine withdraws that entry and reopens the result with the real difference. A
 * {@code DISMISSED} result stays while the difference is the amount dismissed, is resolved on
 * agreement and reopened on any other amount. Reopening keeps the decision's note as history. When
 * the comparison basis goes away (no opening balance, no longer cash scope), a decision on the
 * newest snapshot is {@code SUPERSEDED} like an open result, an accepted one's entry withdrawn.
 *
 * <p>A newer snapshot finalizes the decisions on older ones ({@link
 * ReconciliationHistoryService#isFinalized}): it was compared against a ledger that contains them,
 * so they are history, like a closed period. They are not reopened and their adjusting entries
 * stay; a correction goes into the newest comparison as a new, visible entry.
 *
 * <p>This slice deliberately excludes holdings: an account that holds positions, or one without a
 * transaction ledger, reports {@code CASH_SCOPE_NOT_APPLICABLE}. EPIC 15 adds security-level
 * reconciliation through the already-existing {@code affected_security_id} column.
 */
@Service
public class ReconciliationService {

  private static final String OPEN = ReconciliationResultValues.OPEN;
  private static final String RESOLVED = ReconciliationResultValues.RESOLVED;
  private static final String SUPERSEDED = ReconciliationResultValues.SUPERSEDED;
  private static final String ACCEPTED = ReconciliationResultValues.ACCEPTED;
  private static final String DISMISSED = ReconciliationResultValues.DISMISSED;
  private static final String LIABILITY = "LIABILITY";
  private static final BigDecimal FX_ROUNDING_LIMIT = new BigDecimal("0.05");
  private static final BigDecimal MAX_FEE = new BigDecimal("50.00");

  private final AccessControlService accessControlService;
  private final AccountSnapshotRepository snapshotRepository;
  private final TransactionRepository transactionRepository;
  private final ReconciliationResultRepository resultRepository;
  private final Clock clock;

  public ReconciliationService(
      AccessControlService accessControlService,
      AccountSnapshotRepository snapshotRepository,
      TransactionRepository transactionRepository,
      ReconciliationResultRepository resultRepository,
      Clock clock) {
    this.accessControlService = accessControlService;
    this.snapshotRepository = snapshotRepository;
    this.transactionRepository = transactionRepository;
    this.resultRepository = resultRepository;
    this.clock = clock;
  }

  /**
   * Re-evaluates the newest balance snapshot, if a ledger write on {@code changedDate} can affect
   * it. A later-dated ledger change cannot alter an earlier snapshot and therefore does no work.
   *
   * @param changedBy the member whose write this is; recorded as having withdrawn an accepted
   *     difference's adjusting entry, should the write overtake that acceptance
   */
  @Transactional
  public void reconcileAfterLedgerChange(Account account, LocalDate changedDate, UUID changedBy) {
    latestSnapshot(account.getId())
        .filter(snapshot -> !changedDate.isAfter(snapshot.getSnapshotDate()))
        .ifPresent(snapshot -> reconcile(account, snapshot, changedBy));
  }

  /**
   * Re-evaluates the account's newest observed balance snapshot, if one exists. {@code changedBy}
   * as for {@link #reconcileAfterLedgerChange}.
   */
  @Transactional
  public void reconcileLatest(Account account, UUID changedBy) {
    latestSnapshot(account.getId()).ifPresent(snapshot -> reconcile(account, snapshot, changedBy));
  }

  /**
   * Account-level signal. {@code BALANCE_ONLY} sees the state and reason but not the comparison
   * date or discrepancy amount; {@code READ} and stronger may see those details.
   */
  @Transactional(readOnly = true)
  public ReconciliationStatusResponse status(Account account, AuthenticatedUserPrincipal actor) {
    Optional<AccountSnapshot> latest = latestSnapshot(account.getId());
    if (latest.isEmpty()) {
      return new ReconciliationStatusResponse(
          ReconciliationStatusValues.NEVER, null, null, null, null);
    }

    AccountSnapshot snapshot = latest.get();
    Optional<AccountSnapshot> opening = applicableOpening(account, snapshot.getSnapshotDate());
    if (!cashScopeApplies(account)) {
      return statusResponse(
          actor,
          account,
          ReconciliationStatusValues.NOT_RECONCILABLE,
          ReconciliationStatusValues.CASH_SCOPE_NOT_APPLICABLE,
          snapshot,
          null);
    }
    if (opening.isEmpty()) {
      return statusResponse(
          actor,
          account,
          ReconciliationStatusValues.NOT_RECONCILABLE,
          ReconciliationStatusValues.NO_OPENING_BALANCE,
          snapshot,
          null);
    }

    Optional<ReconciliationResult> persisted =
        resultRepository.findBySnapshotIdAndAffectedSecurityIdIsNull(snapshot.getId());
    if (persisted.isPresent() && OPEN.equals(persisted.get().getStatus())) {
      return statusResponse(
          actor,
          account,
          ReconciliationStatusValues.OPEN_DIFFERENCE,
          null,
          snapshot,
          persisted.get().getDifferenceAmount());
    }
    if (persisted.isPresent() && DISMISSED.equals(persisted.get().getStatus())) {
      return statusResponse(
          actor,
          account,
          ReconciliationStatusValues.DISMISSED_DIFFERENCE,
          null,
          snapshot,
          persisted.get().getDifferenceAmount());
    }
    // An accepted difference agrees through its visible adjusting entry; the engine reopens the
    // result as soon as it no longer does.
    if (persisted.isPresent()
        && List.of(RESOLVED, ACCEPTED).contains(persisted.get().getStatus())) {
      return statusResponse(
          actor, account, ReconciliationStatusValues.RECONCILED, null, snapshot, null);
    }

    // Backward-compatible read for a snapshot that predates this engine: compute the signal
    // without writing from a GET. Normal source writes persist the corresponding result.
    BigDecimal difference = difference(account, opening.get(), snapshot);
    return statusResponse(
        actor,
        account,
        difference.signum() == 0
            ? ReconciliationStatusValues.RECONCILED
            : ReconciliationStatusValues.OPEN_DIFFERENCE,
        null,
        snapshot,
        difference.signum() == 0 ? null : difference);
  }

  /**
   * Takes an accepted result's adjusting entry back out of the ledger: soft-deleted like any manual
   * row (T1), so it stays in the database as history but no longer counts anywhere. {@code
   * withdrawnBy} is the member reopening it, or the one whose write overtook the acceptance or took
   * away its comparison basis.
   */
  void withdrawAdjustment(ReconciliationResult result, UUID withdrawnBy) {
    UUID adjustmentId = result.getResolutionTransactionId();
    if (adjustmentId != null) {
      transactionRepository
          .findByIdForUpdate(adjustmentId)
          .ifPresent(
              adjustment -> {
                adjustment.setDeletedAt(now());
                adjustment.setDeletedBy(withdrawnBy);
                transactionRepository.saveAndFlush(adjustment);
              });
    }
    result.setResolutionTransactionId(null);
  }

  private void reconcile(Account account, AccountSnapshot snapshot, UUID changedBy) {
    // Two concurrent ledger writes on one account both reconcile the same snapshot. The row lock
    // makes the second wait and then see the first's result row, so it updates that row instead of
    // inserting a second one into V60's unique index and failing the member's write with a 409.
    // It is taken before any result or adjustment is touched, retiring included: the order a
    // decision (US-25-03) uses too.
    snapshotRepository.findForUpdate(snapshot.getId(), account.getId());
    if (!cashScopeApplies(account)) {
      retireComparison(account.getId(), snapshot, changedBy);
      return;
    }
    Optional<AccountSnapshot> opening = applicableOpening(account, snapshot.getSnapshotDate());
    if (opening.isEmpty()) {
      retireComparison(account.getId(), snapshot, changedBy);
      return;
    }

    supersedeOlderOpen(account.getId(), snapshot);
    BigDecimal difference = difference(account, opening.get(), snapshot);
    Optional<ReconciliationResult> existing =
        resultRepository.findBySnapshotIdAndAffectedSecurityIdIsNull(snapshot.getId());
    String status = existing.map(ReconciliationResult::getStatus).orElse(null);

    // A member's decision covers one exact amount (US-25-03). While it still holds, it stands.
    if (ACCEPTED.equals(status)) {
      if (difference.signum() == 0 && adjustmentCounts(existing.get(), opening.get())) {
        return;
      }
      // The account stopped agreeing, or it agrees without the adjusting entry (an opening balance
      // stated again on the entry's date already contains it), so the acceptance no longer fits.
      // Withdraw its adjusting entry first: the difference to show is the real one, not one skewed
      // by a stale correction. A member who has since booked the missing row thereby ends up
      // reconciled.
      withdrawAdjustment(existing.get(), changedBy);
      difference = difference(account, opening.get(), snapshot);
    } else if (DISMISSED.equals(status)
        && difference.compareTo(existing.get().getDifferenceAmount()) == 0) {
      return;
    }

    if (difference.signum() == 0) {
      // SUPERSEDED (a lost comparison basis) and RESOLVED stay as they are; anything still open,
      // or a decision the change has overtaken, is resolved.
      existing
          .filter(result -> List.of(OPEN, ACCEPTED, DISMISSED).contains(result.getStatus()))
          .ifPresent(this::resolve);
      return;
    }

    ReconciliationResult result =
        existing.orElseGet(
            () -> {
              ReconciliationResult created = new ReconciliationResult();
              created.setWorkspace(account.getWorkspace());
              created.setAccount(account);
              created.setSnapshot(snapshot);
              return created;
            });
    // The note of an overtaken decision stays: the history still says why it had been decided.
    result.setDifferenceAmount(difference);
    result.setProbableCause(classify(account, opening.get(), snapshot, difference));
    result.setStatus(OPEN);
    result.setResolvedAt(null);
    result.setResolvedBy(null);
    resultRepository.saveAndFlush(result);
  }

  private void supersedeOlderOpen(UUID accountId, AccountSnapshot latest) {
    for (ReconciliationResult result :
        resultRepository.findByAccountIdAndStatusAndAffectedSecurityIdIsNull(accountId, OPEN)) {
      if (!result.getSnapshot().getId().equals(latest.getId())) {
        result.setStatus(SUPERSEDED);
        result.setResolvedAt(now());
        resultRepository.save(result);
      }
    }
    resultRepository.flush();
  }

  // The comparison basis is gone: no opening balance at or before the snapshot, or the account is
  // no longer cash scope. Every open result retires, and so does a decision on the newest snapshot
  // (US-25-03): it covered an amount that can no longer be computed, so an accepted one's adjusting
  // entry leaves the ledger rather than keep moving a balance nothing compares any more. Once the
  // basis is back, the difference shows as OPEN again for a new decision. A decision on an older
  // snapshot is already part of the ledger the newer comparison was taken on and stays.
  private void retireComparison(UUID accountId, AccountSnapshot latest, UUID changedBy) {
    retireAllOpen(accountId);
    resultRepository
        .findBySnapshotIdAndAffectedSecurityIdIsNull(latest.getId())
        .filter(result -> List.of(ACCEPTED, DISMISSED).contains(result.getStatus()))
        .ifPresent(
            result -> {
              withdrawAdjustment(result, changedBy);
              result.setStatus(SUPERSEDED);
              result.setResolvedAt(now());
              result.setResolvedBy(null);
              resultRepository.saveAndFlush(result);
            });
  }

  // Whether an accepted result's adjusting entry is part of the ledger after this opening balance:
  // booked after its date, or on it but after the balance was stated - the rule of
  // TransactionRepository#sumAmountByAccountIdBookedAfter.
  private boolean adjustmentCounts(ReconciliationResult result, AccountSnapshot opening) {
    UUID adjustmentId = result.getResolutionTransactionId();
    if (adjustmentId == null) {
      return false;
    }
    return transactionRepository
        .findById(adjustmentId)
        .filter(
            adjustment ->
                adjustment.getBookingDate().isAfter(opening.getSnapshotDate())
                    || adjustment.getCreatedAt().isAfter(opening.getStatedAt()))
        .isPresent();
  }

  private void retireAllOpen(UUID accountId) {
    for (ReconciliationResult result :
        resultRepository.findByAccountIdAndStatusAndAffectedSecurityIdIsNull(accountId, OPEN)) {
      result.setStatus(SUPERSEDED);
      result.setResolvedAt(now());
      resultRepository.save(result);
    }
    resultRepository.flush();
  }

  // The difference and cause stay as they were: the history shows what was resolved, and the
  // status alone says that it no longer applies.
  private void resolve(ReconciliationResult result) {
    result.setStatus(RESOLVED);
    result.setResolvedAt(now());
    result.setResolvedBy(null);
    resultRepository.saveAndFlush(result);
  }

  private String classify(
      Account account, AccountSnapshot opening, AccountSnapshot snapshot, BigDecimal difference) {
    LocalDate periodStart =
        snapshotRepository
            .findFirstByAccountIdAndOpeningBalanceFalseAndBalanceIsNotNullAndSnapshotDateLessThanOrderBySnapshotDateDescCreatedAtDesc(
                account.getId(), snapshot.getSnapshotDate())
            .map(AccountSnapshot::getSnapshotDate)
            .orElse(opening.getSnapshotDate());
    // The heuristics reason about ledger rows, so they work on the ledger amount that would close
    // the gap, not on the balance difference: a liability's balance moves against its ledger.
    BigDecimal missing = missingLedgerAmount(account, difference);

    if (transactionRepository.existsDuplicateEntrySignature(
        account.getId(), periodStart, snapshot.getSnapshotDate(), missing.negate())) {
      return ReconciliationResultValues.CAUSE_DUPLICATE_ENTRY;
    }
    BigDecimal absolute = missing.abs();
    if (absolute.compareTo(FX_ROUNDING_LIMIT) <= 0
        && transactionRepository.existsLiveConvertedBookedAfter(
            account.getId(), periodStart, snapshot.getSnapshotDate())) {
      return ReconciliationResultValues.CAUSE_FX_ROUNDING;
    }
    // Any cent-precise debit up to 50.00 (1.12, 33.20, ...) in the account's own currency, which
    // is the currency both the snapshot and the derived ledger are expressed in.
    if (missing.signum() < 0
        && absolute.compareTo(MAX_FEE) <= 0
        && absolute.stripTrailingZeros().scale() <= 2) {
      return ReconciliationResultValues.CAUSE_UNRECORDED_FEE;
    }
    return ReconciliationResultValues.CAUSE_UNKNOWN;
  }

  /**
   * The signed ledger amount whose booking would make the derived balance equal the snapshot: the
   * difference itself on an asset, its negation on a liability ({@code derived = opening -
   * ledger}).
   */
  static BigDecimal missingLedgerAmount(Account account, BigDecimal difference) {
    return LIABILITY.equals(account.getNature()) ? difference.negate() : difference;
  }

  private BigDecimal difference(
      Account account, AccountSnapshot opening, AccountSnapshot snapshot) {
    BigDecimal ledger =
        transactionRepository
            .sumAmountByAccountIdBookedAfter(
                account.getId(),
                opening.getSnapshotDate(),
                opening.getStatedAt(),
                snapshot.getSnapshotDate())
            .orElse(BigDecimal.ZERO);
    BigDecimal derived =
        LIABILITY.equals(account.getNature())
            ? opening.getBalance().subtract(ledger)
            : opening.getBalance().add(ledger);
    return snapshot
        .getBalance()
        .subtract(derived)
        .setScale(FxRateService.MONEY_SCALE, RoundingMode.HALF_UP);
  }

  private Optional<AccountSnapshot> applicableOpening(Account account, LocalDate asOf) {
    if (!cashScopeApplies(account)) {
      return Optional.empty();
    }
    return snapshotRepository
        .findByAccountIdAndOpeningBalanceTrue(account.getId())
        .filter(opening -> !opening.getSnapshotDate().isAfter(asOf));
  }

  private static boolean cashScopeApplies(Account account) {
    return account.isHasTransactions() && !account.isHoldsPositions();
  }

  private Optional<AccountSnapshot> latestSnapshot(UUID accountId) {
    return snapshotRepository
        .findFirstByAccountIdAndOpeningBalanceFalseAndBalanceIsNotNullOrderBySnapshotDateDescCreatedAtDesc(
            accountId);
  }

  private ReconciliationStatusResponse statusResponse(
      AuthenticatedUserPrincipal actor,
      Account account,
      String status,
      String reason,
      AccountSnapshot snapshot,
      BigDecimal difference) {
    boolean mayReadDetails =
        AccessLevelValues.Rank.valueOf(accessControlService.accountAccessLevel(actor, account))
                .compareTo(AccessLevelValues.Rank.READ)
            >= 0;
    return new ReconciliationStatusResponse(
        status,
        reason,
        mayReadDetails ? snapshot.getSnapshotDate() : null,
        mayReadDetails ? difference : null,
        mayReadDetails && difference != null ? snapshot.getCurrency() : null);
  }

  OffsetDateTime now() {
    return OffsetDateTime.now(clock);
  }
}
