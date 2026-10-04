package com.trackmywealth.backend.service;

import com.trackmywealth.backend.dto.AccessLevelValues;
import com.trackmywealth.backend.dto.ReconciliationResultResponse;
import com.trackmywealth.backend.dto.ReconciliationResultValues;
import com.trackmywealth.backend.entity.Account;
import com.trackmywealth.backend.entity.AccountSnapshot;
import com.trackmywealth.backend.entity.ReconciliationResult;
import com.trackmywealth.backend.entity.Transaction;
import com.trackmywealth.backend.error.ReconciliationFinalizedException;
import com.trackmywealth.backend.error.ReconciliationStaleException;
import com.trackmywealth.backend.repository.ReconciliationResultRepository;
import com.trackmywealth.backend.repository.TransactionRepository;
import com.trackmywealth.backend.security.AuthenticatedUserPrincipal;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * US-25-03/FR-REC-004/FR-STA-003: a member's decision on a reconciliation difference. Every account
 * either agrees with its provider or has a documented, visible reason why not:
 *
 * <ul>
 *   <li><b>Add the missing transaction</b> needs no endpoint here: the member records it as any
 *       other row (EPIC 07), and the engine ({@link ReconciliationService}) resolves the
 *       difference.
 *   <li><b>{@link #accept}</b> takes the provider's figure: a visible {@code VALUATION_ADJUSTMENT}
 *       row for the difference, dated to the snapshot, closes the gap - never a silent correction.
 *       It is a balance correction, not spending or income, so no cash-flow figure counts it
 *       ({@link CashFlowService} sums named types only) and it is not categorized. It can only be
 *       taken back by {@link #reopen}: correcting, removing, restoring or categorizing it directly
 *       is a 409 {@code RECONCILIATION_ADJUSTMENT_LOCKED}, so the result never points at a row that
 *       no longer closes its gap.
 *   <li><b>{@link #dismiss}</b> documents the difference with a reason; it no longer raises the
 *       open warning.
 *   <li><b>{@link #reopen}</b> turns either decision back into an open difference; reopening an
 *       accepted one withdraws its adjusting row.
 * </ul>
 *
 * <p>A newer snapshot finalizes the decisions on older ones, like a closed period: it was compared
 * against a ledger that contains them. Reopening or deciding on such a result again is a 409 {@code
 * RECONCILIATION_FINALIZED}, and its adjusting row stays; a correction goes into the newest
 * comparison as a new, visible entry.
 *
 * <p>Each needs {@code EDIT} on the account, as recording a transaction there does, and the
 * result's version in {@code If-Match} (ADR 0004). Only the account's newest comparison can be
 * decided on: a result a newer snapshot superseded, or one not in the state the decision applies
 * to, is a 409 {@code RECONCILIATION_STALE} carrying the current figure. Accept and dismiss first
 * re-evaluate the comparison, so a member never confirms a figure that is no longer true.
 *
 * <p>Every check that can answer {@code RECONCILIATION_STALE} or {@code RECONCILIATION_FINALIZED}
 * runs before the decision writes anything, so such a 409 commits ({@code noRollbackFor}) only the
 * engine's re-evaluation: the reload then shows the figure and version the 409 named, and a retry
 * can succeed. That re-evaluation is an ordinary engine run with the requesting member as its
 * actor: should it withdraw an adjusting entry the ledger has overtaken, {@code deleted_by} names
 * that member even though their own request was answered with the 409 - the withdrawal is what the
 * engine would have done on their next read anyway, and V39 requires an actor for it.
 */
@Service
public class ReconciliationDecisionService {

  private final AccountLookupService accountLookupService;
  private final AccessControlService accessControlService;
  private final ReconciliationService reconciliationService;
  private final ReconciliationHistoryService historyService;
  private final ReconciliationResultRepository resultRepository;
  private final TransactionRepository transactionRepository;
  private final SettlementDetectionService settlementDetectionService;
  private final TransferDetectionService transferDetectionService;
  private final VersionPreconditionService versionPreconditionService;

  public ReconciliationDecisionService(
      AccountLookupService accountLookupService,
      AccessControlService accessControlService,
      ReconciliationService reconciliationService,
      ReconciliationHistoryService historyService,
      ReconciliationResultRepository resultRepository,
      TransactionRepository transactionRepository,
      SettlementDetectionService settlementDetectionService,
      TransferDetectionService transferDetectionService,
      VersionPreconditionService versionPreconditionService) {
    this.accountLookupService = accountLookupService;
    this.accessControlService = accessControlService;
    this.reconciliationService = reconciliationService;
    this.historyService = historyService;
    this.resultRepository = resultRepository;
    this.transactionRepository = transactionRepository;
    this.settlementDetectionService = settlementDetectionService;
    this.transferDetectionService = transferDetectionService;
    this.versionPreconditionService = versionPreconditionService;
  }

  @Transactional(
      noRollbackFor = {ReconciliationStaleException.class, ReconciliationFinalizedException.class})
  public ReconciliationResultResponse accept(
      UUID accountId,
      UUID resultId,
      String note,
      Integer expectedVersion,
      AuthenticatedUserPrincipal actor) {
    Account account = accountLookupService.findAccountOrThrow(accountId, actor);
    ReconciliationResult result = openResult(account, resultId, expectedVersion, actor);
    AccountSnapshot snapshot = result.getSnapshot();
    String reason = note.strip();

    Transaction adjustment = new Transaction();
    adjustment.setWorkspace(account.getWorkspace());
    adjustment.setAccount(account);
    adjustment.setTransactionType(TransactionService.VALUATION_ADJUSTMENT);
    adjustment.setBookingDate(snapshot.getSnapshotDate());
    // The ledger amount that makes the derived balance equal the snapshot - on a liability the
    // balance moves against its ledger - in the account's own currency, as the snapshot is.
    adjustment.setAmount(
        ReconciliationService.missingLedgerAmount(account, result.getDifferenceAmount()));
    adjustment.setCurrency(snapshot.getCurrency());
    // No stored description: a client labels the row in its own language from its type and
    // reconciliationAdjustment state. The member's reason is the row's note.
    adjustment.setNotes(reason);
    adjustment.setSource(TransactionService.MANUAL);
    // The owner link, not the type, makes it a reconciliation adjustment (V63).
    adjustment.setReconciliationResultId(result.getId());
    adjustment.setCreatedBy(actor.userId());
    Transaction saved = transactionRepository.saveAndFlush(adjustment);

    decide(result, ReconciliationResultValues.ACCEPTED, reason, actor);
    result.setResolutionTransactionId(saved.getId());
    resultRepository.saveAndFlush(result);
    // The card's balance moved, which can complete a settlement pair (as any ledger write can).
    detectAfterLedgerChange(account, snapshot);
    return historyService.toResponse(result);
  }

  @Transactional(
      noRollbackFor = {ReconciliationStaleException.class, ReconciliationFinalizedException.class})
  public ReconciliationResultResponse dismiss(
      UUID accountId,
      UUID resultId,
      String note,
      Integer expectedVersion,
      AuthenticatedUserPrincipal actor) {
    Account account = accountLookupService.findAccountOrThrow(accountId, actor);
    ReconciliationResult result = openResult(account, resultId, expectedVersion, actor);
    decide(result, ReconciliationResultValues.DISMISSED, note.strip(), actor);
    resultRepository.saveAndFlush(result);
    return historyService.toResponse(result);
  }

  /**
   * FR-STA-003: back to an open difference, from {@code ACCEPTED} or {@code DISMISSED}. The
   * comparison is evaluated again right away, so the response shows the difference as it is now -
   * and, should the account agree by then, a {@code RESOLVED} result.
   */
  @Transactional(
      noRollbackFor = {ReconciliationStaleException.class, ReconciliationFinalizedException.class})
  public ReconciliationResultResponse reopen(
      UUID accountId, UUID resultId, Integer expectedVersion, AuthenticatedUserPrincipal actor) {
    Account account = accountLookupService.findAccountOrThrow(accountId, actor);
    ReconciliationResult result = decidableResult(account, resultId, expectedVersion, actor);
    // Snapshot before result/adjustment: the engine itself locks the snapshot before it updates a
    // reconciliation result or withdraws an adjustment, so reopen must use the same order for both
    // ACCEPTED and DISMISSED paths.
    reconciliationService.reconcileLatest(account, actor.userId());
    requireCurrentComparison(
        result,
        List.of(ReconciliationResultValues.ACCEPTED, ReconciliationResultValues.DISMISSED),
        "Only an accepted or dismissed difference can be reopened.");

    boolean wasAccepted = ReconciliationResultValues.ACCEPTED.equals(result.getStatus());
    if (wasAccepted) {
      reconciliationService.withdrawAdjustment(result, actor.userId());
    }
    result.setStatus(ReconciliationResultValues.OPEN);
    result.setResolvedAt(null);
    result.setResolvedBy(null);
    resultRepository.saveAndFlush(result);
    reconciliationService.reconcileLatest(account, actor.userId());
    if (wasAccepted) {
      detectAfterLedgerChange(account, result.getSnapshot());
    }
    return historyService.toResponse(result);
  }

  // Accept and dismiss decide on an open difference only, and only on the figure as it is now.
  private ReconciliationResult openResult(
      Account account, UUID resultId, Integer expectedVersion, AuthenticatedUserPrincipal actor) {
    ReconciliationResult result = decidableResult(account, resultId, expectedVersion, actor);
    BigDecimal shown = result.getDifferenceAmount();
    reconciliationService.reconcileLatest(account, actor.userId());
    requireCurrentComparison(
        result,
        List.of(ReconciliationResultValues.OPEN),
        "Only an open difference can be accepted or dismissed.");
    if (shown.compareTo(result.getDifferenceAmount()) != 0) {
      // Only a result older than the engine's synchronous hooks can drift like this: every source
      // write re-evaluates in its own transaction. The 409 commits the refresh (noRollbackFor), so
      // the member's reload shows the new figure and version.
      throw stale(result, "The difference has changed since it was computed.");
    }
    return result;
  }

  private ReconciliationResult decidableResult(
      Account account, UUID resultId, Integer expectedVersion, AuthenticatedUserPrincipal actor) {
    // Authorization always precedes object/version disclosure, and an id the member may not use is
    // the audited 404 before any lock is taken. After that, use the same lock order as an ordinary
    // ledger write: affected cards -> transfer-detection workspace -> snapshot, so a decision and
    // a write cannot deadlock. The result, and so its version, is read only under those locks.
    accessControlService.requireAccountAccess(actor, account, AccessLevelValues.EDIT);
    historyService.requireResultExists(account, resultId, actor);
    settlementDetectionService.lockAffectedCards(account);
    transferDetectionService.lockForWrite(account);
    ReconciliationResult result = historyService.findResultOrThrow(account, resultId, actor);
    versionPreconditionService.requireCurrent(
        expectedVersion, result.getVersion(), ReconciliationHistoryService.RESOURCE_NAME);
    return result;
  }

  private void requireCurrentComparison(
      ReconciliationResult result, List<String> allowedStatuses, String wrongStatusDetail) {
    if (historyService.isFinalized(result)) {
      // A newer snapshot was compared against a ledger containing this decision: it is history.
      throw new ReconciliationFinalizedException(result.getStatus());
    }
    if (!historyService.comparesLatestSnapshot(result)) {
      throw stale(result, "A newer snapshot has replaced this comparison.");
    }
    if (!allowedStatuses.contains(result.getStatus())) {
      throw stale(result, wrongStatusDetail);
    }
  }

  private void decide(
      ReconciliationResult result, String status, String note, AuthenticatedUserPrincipal actor) {
    result.setStatus(status);
    result.setResolutionNote(note);
    result.setResolvedAt(reconciliationService.now());
    result.setResolvedBy(actor.userId());
  }

  private void detectAfterLedgerChange(Account account, AccountSnapshot snapshot) {
    // The decision already holds these locks in the same order as ordinary transaction writes;
    // the detection services are intentionally re-entrant within the same transaction.
    settlementDetectionService.detectAfterWrite(account, snapshot.getSnapshotDate());
    transferDetectionService.detectAfterWrite(account, snapshot.getSnapshotDate());
  }

  private static ReconciliationStaleException stale(ReconciliationResult result, String detail) {
    return new ReconciliationStaleException(
        detail + " Reload the account's reconciliation and decide again.",
        result.getStatus(),
        result.getDifferenceAmount());
  }
}
