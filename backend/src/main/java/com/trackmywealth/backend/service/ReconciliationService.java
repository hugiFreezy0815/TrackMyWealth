package com.trackmywealth.backend.service;

import com.trackmywealth.backend.dto.AccessLevelValues;
import com.trackmywealth.backend.dto.ReconciliationResultResponse;
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
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
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
 * <p>This slice deliberately excludes holdings: an account that holds positions, or one without a
 * transaction ledger, reports {@code CASH_SCOPE_NOT_APPLICABLE}. EPIC 15 adds security-level
 * reconciliation through the already-existing {@code affected_security_id} column.
 */
@Service
public class ReconciliationService {

  private static final String OPEN = ReconciliationResultValues.OPEN;
  private static final String RESOLVED = ReconciliationResultValues.RESOLVED;
  private static final String SUPERSEDED = ReconciliationResultValues.SUPERSEDED;
  private static final String LIABILITY = "LIABILITY";
  private static final int MAX_PAGE_SIZE = 200;
  private static final BigDecimal FX_ROUNDING_LIMIT = new BigDecimal("0.05");
  private static final BigDecimal MAX_FEE = new BigDecimal("50.00");

  private final AccountLookupService accountLookupService;
  private final AccessControlService accessControlService;
  private final AccountSnapshotRepository snapshotRepository;
  private final TransactionRepository transactionRepository;
  private final ReconciliationResultRepository resultRepository;
  private final Clock clock;

  public ReconciliationService(
      AccountLookupService accountLookupService,
      AccessControlService accessControlService,
      AccountSnapshotRepository snapshotRepository,
      TransactionRepository transactionRepository,
      ReconciliationResultRepository resultRepository,
      Clock clock) {
    this.accountLookupService = accountLookupService;
    this.accessControlService = accessControlService;
    this.snapshotRepository = snapshotRepository;
    this.transactionRepository = transactionRepository;
    this.resultRepository = resultRepository;
    this.clock = clock;
  }

  /**
   * Re-evaluates the newest balance snapshot, if a ledger write on {@code changedDate} can affect
   * it. A later-dated ledger change cannot alter an earlier snapshot and therefore does no work.
   */
  @Transactional
  public void reconcileAfterLedgerChange(Account account, LocalDate changedDate) {
    latestSnapshot(account.getId())
        .filter(snapshot -> !changedDate.isAfter(snapshot.getSnapshotDate()))
        .ifPresent(snapshot -> reconcile(account, snapshot));
  }

  /** Re-evaluates the account's newest observed balance snapshot, if one exists. */
  @Transactional
  public void reconcileLatest(Account account) {
    latestSnapshot(account.getId()).ifPresent(snapshot -> reconcile(account, snapshot));
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
    if (persisted.isPresent() && RESOLVED.equals(persisted.get().getStatus())) {
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

  /** Detailed history is transaction-sensitive and therefore requires READ, not BALANCE_ONLY. */
  @Transactional(readOnly = true)
  public Page<ReconciliationResultResponse> list(
      UUID accountId, Pageable pageable, AuthenticatedUserPrincipal actor) {
    Account account = accountLookupService.findAccountOrThrow(accountId, actor);
    accessControlService.requireAccountAccess(actor, account, AccessLevelValues.READ);
    Pageable bounded =
        PageRequest.of(pageable.getPageNumber(), Math.min(pageable.getPageSize(), MAX_PAGE_SIZE));
    return resultRepository
        .findByAccountIdAndAffectedSecurityIdIsNullOrderByCreatedAtDesc(accountId, bounded)
        .map(this::toResponse);
  }

  private void reconcile(Account account, AccountSnapshot snapshot) {
    if (!cashScopeApplies(account)) {
      retireAllOpen(account.getId());
      return;
    }
    Optional<AccountSnapshot> opening = applicableOpening(account, snapshot.getSnapshotDate());
    if (opening.isEmpty()) {
      retireAllOpen(account.getId());
      return;
    }

    // Two concurrent ledger writes on one account both reconcile the same snapshot. The row lock
    // makes the second wait and then see the first's result row, so it updates that row instead of
    // inserting a second one into V60's unique index and failing the member's write with a 409.
    snapshotRepository.findForUpdate(snapshot.getId(), account.getId());
    supersedeOlderOpen(account.getId(), snapshot);
    BigDecimal difference = difference(account, opening.get(), snapshot);
    Optional<ReconciliationResult> existing =
        resultRepository.findBySnapshotIdAndAffectedSecurityIdIsNull(snapshot.getId());

    if (difference.signum() == 0) {
      existing.filter(result -> OPEN.equals(result.getStatus())).ifPresent(this::resolve);
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
    // A future US-25-03 decision is authoritative. This engine may reopen only its own automatic
    // states; ACCEPTED/DISMISSED are member decisions and are not silently overwritten.
    if (!List.of(OPEN, RESOLVED, SUPERSEDED).contains(result.getStatus())) {
      return;
    }
    result.setDifferenceAmount(difference);
    result.setProbableCause(classify(account, opening.get(), snapshot, difference));
    result.setStatus(OPEN);
    result.setResolvedAt(null);
    result.setResolvedBy(null);
    result.setResolutionNote(null);
    result.setResolutionTransactionId(null);
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
                account.getId(), opening.getSnapshotDate(), snapshot.getSnapshotDate())
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

  private ReconciliationResultResponse toResponse(ReconciliationResult result) {
    return new ReconciliationResultResponse(
        result.getId(),
        result.getAccount().getId(),
        result.getSnapshot().getId(),
        result.getSnapshot().getSnapshotDate(),
        result.getDifferenceAmount(),
        result.getSnapshot().getCurrency(),
        result.getProbableCause(),
        result.getStatus(),
        result.getResolvedAt(),
        result.getCreatedAt());
  }

  private OffsetDateTime now() {
    return OffsetDateTime.now(clock);
  }
}
