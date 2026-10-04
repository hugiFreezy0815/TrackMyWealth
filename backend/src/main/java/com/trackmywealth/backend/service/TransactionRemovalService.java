package com.trackmywealth.backend.service;

import com.trackmywealth.backend.dto.AccessLevelValues;
import com.trackmywealth.backend.dto.SettlementMatchValues;
import com.trackmywealth.backend.dto.TransactionRemovalResponse;
import com.trackmywealth.backend.dto.TransactionRemovalValues;
import com.trackmywealth.backend.dto.TransactionResponse;
import com.trackmywealth.backend.entity.Account;
import com.trackmywealth.backend.entity.SettlementMatch;
import com.trackmywealth.backend.entity.Transaction;
import com.trackmywealth.backend.repository.SettlementMatchRepository;
import com.trackmywealth.backend.repository.TransactionRepository;
import com.trackmywealth.backend.security.AuthenticatedUserPrincipal;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * US-07-02/FR-LIF-002/002a/002b/003/006/007: removing a transaction without ever mutating a
 * financial field (RULE-024). The system decides how, from the row's provenance - the member only
 * says "delete" (FR-LIF-003):
 *
 * <ul>
 *   <li><b>T1, a manually entered row</b> ({@code source = MANUAL}): soft delete. It disappears
 *       from every view and figure (the entity's {@code @SQLRestriction}), is restorable for
 *       {@value #RESTORE_WINDOW_DAYS} days ({@link #restore}), and is never purged (V39, issue
 *       #143).
 *   <li><b>T2, an imported row</b>: void. The original is marked voided with a required reason and
 *       stays listed as voided; a reversing row of the same type with every amount and the quantity
 *       negated is added, dated to the void, linked by {@code replaces_transaction_id}. Every
 *       figure leaves both out - balances too, on every date, so history reads as restated (see
 *       {@code TransactionRepository#sumAmountByAccountIdAsOf}). Restoring the void within {@value
 *       #RESTORE_WINDOW_DAYS} days (US-07-07) keeps both and adds an ordinary copy of the original,
 *       linked by {@code restores_transaction_id} (V50).
 * </ul>
 *
 * <p>Nothing goes silently (FR-LIF-007): a card purchase's linked FEE row is removed with it, and
 * every settlement match the removal breaks is dissolved - a confirmed match's other leg becomes an
 * ordinary payment or credit again. The response names every row affected. Only open matches
 * (proposed, confirmed) are removed: a rejected one stays, beside a voided row as history and
 * beside a soft-deleted one so that a restore cannot re-propose or auto-confirm the pair the member
 * rejected. Match queries leave a match out while one of its legs is deleted.
 *
 * <p>Needs EDIT on the account, the same as recording there. An accepted reconciliation
 * difference's adjusting entry is neither removed nor restored here: its result's reopen does that
 * (US-25-03). T3 - a reconciled row whose void reopens its reconciliation - arrives with the import
 * rollback (US-07-05).
 */
@Service
public class TransactionRemovalService {

  static final int RESTORE_WINDOW_DAYS = 30;
  private static final int MAX_REASON_LENGTH = 500;
  private static final String NOT_FOUND = "Not found.";

  private final AccountLookupService accountLookupService;
  private final AccessControlService accessControlService;
  private final TransactionRepository transactionRepository;
  private final SettlementMatchRepository settlementMatchRepository;
  private final SettlementDetectionService settlementDetectionService;
  private final TransferDetectionService transferDetectionService;
  private final TransactionService transactionService;
  private final CategorizationService categorizationService;
  private final BusinessDateService businessDateService;
  private final Clock clock;
  private final VersionPreconditionService versionPreconditionService;
  private final ReconciliationService reconciliationService;
  private final ReconciliationAdjustmentService reconciliationAdjustmentService;

  public TransactionRemovalService(
      AccountLookupService accountLookupService,
      AccessControlService accessControlService,
      TransactionRepository transactionRepository,
      SettlementMatchRepository settlementMatchRepository,
      SettlementDetectionService settlementDetectionService,
      TransferDetectionService transferDetectionService,
      TransactionService transactionService,
      CategorizationService categorizationService,
      BusinessDateService businessDateService,
      Clock clock,
      VersionPreconditionService versionPreconditionService,
      ReconciliationService reconciliationService,
      ReconciliationAdjustmentService reconciliationAdjustmentService) {
    this.accountLookupService = accountLookupService;
    this.accessControlService = accessControlService;
    this.transactionRepository = transactionRepository;
    this.settlementMatchRepository = settlementMatchRepository;
    this.settlementDetectionService = settlementDetectionService;
    this.transferDetectionService = transferDetectionService;
    this.transactionService = transactionService;
    this.categorizationService = categorizationService;
    this.businessDateService = businessDateService;
    this.clock = clock;
    this.versionPreconditionService = versionPreconditionService;
    this.reconciliationService = reconciliationService;
    this.reconciliationAdjustmentService = reconciliationAdjustmentService;
  }

  /**
   * Removes the transaction the way its provenance requires. {@code reason} is required for a void
   * (422 without it) and ignored for a soft delete; each row's {@code removal} tells a client in
   * advance which applies.
   */
  @Transactional
  public TransactionRemovalResponse remove(
      UUID accountId,
      UUID transactionId,
      String reason,
      Integer expectedVersion,
      AuthenticatedUserPrincipal actor) {
    Transaction original = lockActiveTransaction(accountId, transactionId, actor);
    Account account = original.getAccount();
    reconciliationAdjustmentService.requireNotAdjustment(original);
    String removal = TransactionService.removalOf(original);
    if (removal == null) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, notRemovable(original));
    }
    boolean softDelete = TransactionRemovalValues.SOFT_DELETE.equals(removal);
    String voidReason = softDelete ? null : requireReason(reason);

    List<Transaction> affected = new ArrayList<>();
    affected.add(original);
    // A card purchase's disclosed FX fee is part of it (US-09-04); a fee removed on its own before
    // is no longer found (voided, or hidden once soft-deleted).
    transactionRepository
        .findByRelatedTransactionId(original.getId())
        .filter(fee -> fee.getVoidedAt() == null)
        .ifPresent(affected::add);
    // US-10-01: a two-sided transfer is one entry - removing its incoming leg takes the outgoing
    // one too (the other direction is the lookup just above).
    if (isTransferLeg(original) && original.getRelatedTransactionId() != null) {
      transactionRepository
          .findByIdForUpdate(original.getRelatedTransactionId())
          .filter(debit -> debit.getVoidedAt() == null)
          .ifPresent(affected::add);
    }
    requireEditOnOtherAccounts(affected, account, actor);
    versionPreconditionService.requireCurrent(
        expectedVersion, original.getVersion(), TransactionService.VERSIONED_RESOURCE);

    OffsetDateTime now = OffsetDateTime.now(clock);
    SortedSet<UUID> unmatched = new TreeSet<>();
    List<Transaction> reversals = new ArrayList<>();
    for (Transaction row : affected) {
      dissolveMatches(row, unmatched);
      if (softDelete) {
        row.setDeletedAt(now);
        row.setDeletedBy(actor.userId());
        transactionRepository.saveAndFlush(row);
      } else {
        row.setVoidedAt(now);
        row.setVoidedBy(actor.userId());
        row.setVoidReason(voidReason);
        transactionRepository.saveAndFlush(row);
        reversals.add(transactionRepository.saveAndFlush(reversalOf(row, actor.userId())));
      }
    }
    affected.forEach(row -> unmatched.remove(row.getId()));
    // Match dissolution may have changed the rows again; flush so every returned version - and
    // the ETag - is the one stored, not one Hibernate would only write at commit.
    transactionRepository.flush();
    reconcileAfterLedgerChanges(affected, actor.userId());
    return new TransactionRemovalResponse(
        removal,
        VersionPreconditionService.persistedVersion(
            original.getVersion(), TransactionService.VERSIONED_RESOURCE),
        transactionService.toResponses(affected),
        transactionService.toResponses(reversals),
        List.copyOf(unmatched),
        List.of());
  }

  // Why a row the API still lists cannot be removed (again).
  private String notRemovable(Transaction row) {
    if (row.isReversal()) {
      return "A reversing entry cannot be removed on its own; it goes with its original.";
    }
    return transactionRepository
        .findByRestoresTransactionId(row.getId())
        .map(
            copy ->
                "This transaction is voided; its void was restored as transaction "
                    + copy.getId()
                    + ", which is the entry to remove.")
        .orElse("This transaction is already voided.");
  }

  /**
   * FR-LIF-006: restores either removal tier within {@value #RESTORE_WINDOW_DAYS} days of the
   * removal. A soft-deleted T1 row is unhidden in place. A voided T2 row is re-instated by a copy
   * (see {@link #restoreVoided}). Either way the rows removed together with it (a purchase's FEE
   * row, a transfer's other leg) come back too, and settlement and transfer detection run again on
   * every account involved.
   */
  @Transactional
  public TransactionRemovalResponse restore(
      UUID accountId,
      UUID transactionId,
      Integer expectedVersion,
      AuthenticatedUserPrincipal actor) {
    Account account = requireEditable(accountId, actor);
    lockCards(accountsToLockForRestore(account, transactionId, actor), transactionId);
    Transaction removed =
        transactionRepository
            .findByIdIncludingDeletedForUpdate(transactionId)
            .filter(row -> row.getAccount().getId().equals(account.getId()))
            .orElseThrow(
                () -> accessControlService.denyAsNotFound(actor, "Transaction", transactionId));
    reconciliationAdjustmentService.requireNotAdjustment(removed);
    if (removed.getDeletedAt() != null) {
      return restoreSoftDeleted(account, removed, expectedVersion, actor);
    }
    if (removed.getVoidedAt() != null) {
      return restoreVoided(account, removed, expectedVersion, actor);
    }
    throw new ResponseStatusException(HttpStatus.CONFLICT, "This transaction is not removed.");
  }

  private TransactionRemovalResponse restoreSoftDeleted(
      Account account,
      Transaction deleted,
      Integer expectedVersion,
      AuthenticatedUserPrincipal actor) {
    if (deleted.getDeletedAt().isBefore(restoreWindowStart())) {
      throw expiredRestore("deleted");
    }
    List<Transaction> restored = new ArrayList<>();
    restored.add(deleted);
    // Only a fee deleted in the same step: one the member deleted separately stays deleted.
    for (Transaction fee : transactionRepository.findDeletedFeeRowsForUpdate(deleted.getId())) {
      if (fee.getDeletedAt().isEqual(deleted.getDeletedAt())) {
        restored.add(fee);
      }
    }
    // US-10-01: the outgoing leg of a two-sided transfer comes back with its incoming leg.
    if (isTransferLeg(deleted) && deleted.getRelatedTransactionId() != null) {
      transactionRepository
          .findByIdIncludingDeletedForUpdate(deleted.getRelatedTransactionId())
          .filter(debit -> debit.getDeletedAt() != null)
          .filter(debit -> debit.getDeletedAt().isEqual(deleted.getDeletedAt()))
          .ifPresent(restored::add);
    }
    requireEditOnOtherAccounts(restored, account, actor);
    requireNotCorrected(deleted, restored);
    versionPreconditionService.requireCurrent(
        expectedVersion, deleted.getVersion(), TransactionService.VERSIONED_RESOURCE);
    LocalDate earliest = deleted.getBookingDate();
    for (Transaction row : restored) {
      row.setDeletedAt(null);
      row.setDeletedBy(null);
      transactionRepository.saveAndFlush(row);
    }
    detectAfterRestore(restored, earliest, actor.userId());
    transactionRepository.flush();
    return new TransactionRemovalResponse(
        TransactionRemovalValues.SOFT_DELETE,
        VersionPreconditionService.persistedVersion(
            deleted.getVersion(), TransactionService.VERSIONED_RESOURCE),
        transactionService.toResponses(restored),
        List.of(),
        List.of(),
        List.of());
  }

  /**
   * US-07-07: re-instates a void without touching it. The voided rows keep their void metadata and
   * their reversals stay; each gets an ordinary copy - same account, type, date, amounts,
   * provenance and source data - that points back at it through {@code restores_transaction_id}.
   * The ledger then reads A, -A, A': balances include the original effect again from the copy on,
   * while history still shows the void. The copy is a normal row (categorized like a new one, with
   * a member's override carried over; matched again by detection), so it can be categorized,
   * corrected, removed and restored again like any other.
   *
   * <p>A FEE row or incoming transfer leg voided together with its purchase or outgoing leg is
   * restored through that head row, so the group always comes back whole and its copies link to
   * each other, never to a voided row. The window counts from the void. A void already restored or
   * a row since corrected cannot be restored (409).
   */
  private TransactionRemovalResponse restoreVoided(
      Account account,
      Transaction voided,
      Integer expectedVersion,
      AuthenticatedUserPrincipal actor) {
    Transaction head = groupHeadOf(voided);
    if (head.getVoidedAt().isBefore(restoreWindowStart())) {
      throw expiredRestore("voided");
    }
    List<Transaction> group = new ArrayList<>();
    group.add(head);
    // Only rows voided in the same step: a fee voided on its own earlier stays voided.
    transactionRepository.findVoidedDependantsForUpdate(head.getId()).stream()
        .filter(row -> row.getVoidedAt().isEqual(head.getVoidedAt()))
        .forEach(group::add);
    requireEditOnOtherAccounts(group, account, actor);
    requireNotCorrected(head, group);
    requireNotRestored(group);
    UUID parent = currentVersionOf(head.getRelatedTransactionId());
    requireParentInEffect(parent);
    versionPreconditionService.requireCurrent(
        expectedVersion, voided.getVersion(), TransactionService.VERSIONED_RESOURCE);

    Map<UUID, Transaction> copies = new LinkedHashMap<>();
    Transaction headCopy = reinstate(head, parent, actor);
    copies.put(head.getId(), headCopy);
    for (Transaction dependant : group.subList(1, group.size())) {
      copies.put(dependant.getId(), reinstate(dependant, headCopy.getId(), actor));
    }
    carryRejectedMatches(copies);
    LocalDate earliest =
        group.stream().map(Transaction::getBookingDate).min(LocalDate::compareTo).orElseThrow();
    detectAfterRestore(List.copyOf(copies.values()), earliest, actor.userId());
    // Detection may flag the copies after their own flush; flush so returned versions are stored.
    transactionRepository.flush();
    return new TransactionRemovalResponse(
        TransactionRemovalValues.VOID,
        VersionPreconditionService.persistedVersion(
            voided.getVersion(), TransactionService.VERSIONED_RESOURCE),
        transactionService.toResponses(group),
        List.of(),
        List.of(),
        transactionService.toResponses(List.copyOf(copies.values())));
  }

  // A rejected match is a member's decision about this transaction, and the void kept it (see the
  // class comment). The copy is the same transaction, so it inherits the decision - before
  // detection runs, which would otherwise propose or even confirm the very pair the member
  // rejected.
  private void carryRejectedMatches(Map<UUID, Transaction> copies) {
    List<SettlementMatch> carried = new ArrayList<>();
    for (Map.Entry<UUID, Transaction> entry : copies.entrySet()) {
      for (SettlementMatch rejected :
          settlementMatchRepository.findByTransactionId(entry.getKey())) {
        if (!SettlementMatchValues.REJECTED.equals(rejected.getStatus())) {
          continue;
        }
        SettlementMatch match = new SettlementMatch();
        match.setWorkspace(rejected.getWorkspace());
        match.setCardAccount(rejected.getCardAccount());
        match.setPaymentTransaction(copyOf(rejected.getPaymentTransaction(), copies));
        match.setCardTransaction(copyOf(rejected.getCardTransaction(), copies));
        match.setStatus(SettlementMatchValues.REJECTED);
        match.setMatchBasis(rejected.getMatchBasis());
        match.setMatchKind(rejected.getMatchKind());
        match.setDecidedBy(rejected.getDecidedBy());
        match.setDecidedAt(rejected.getDecidedAt());
        carried.add(match);
      }
    }
    settlementMatchRepository.saveAllAndFlush(carried);
  }

  private static Transaction copyOf(Transaction leg, Map<UUID, Transaction> copies) {
    return leg == null ? null : copies.getOrDefault(leg.getId(), leg);
  }

  // A head row voided on its own (a fee whose purchase stayed) links to its parent as it stands
  // now: if that parent was itself voided and restored since, to the copy that re-instated it.
  private UUID currentVersionOf(UUID transactionId) {
    UUID current = transactionId;
    while (current != null) {
      Optional<Transaction> copy = transactionRepository.findByRestoresTransactionId(current);
      if (copy.isEmpty()) {
        return current;
      }
      current = copy.get().getId();
    }
    return null;
  }

  // The row a group was voided through: a FEE row's purchase or an incoming leg's outgoing leg,
  // when voided in the same step; otherwise the row itself (e.g. a fee voided on its own).
  private Transaction groupHeadOf(Transaction voided) {
    if (voided.getRelatedTransactionId() == null) {
      return voided;
    }
    return transactionRepository
        .findByIdIncludingDeletedForUpdate(voided.getRelatedTransactionId())
        .filter(parent -> parent.getVoidedAt() != null)
        .filter(parent -> parent.getVoidedAt().isEqual(voided.getVoidedAt()))
        .orElse(voided);
  }

  // A row voided on its own (a waived fee) belongs to its parent. While that parent is voided too,
  // a copy would count without it and stay behind when the parent's own void is restored or voided
  // again, so the parent comes back first.
  private void requireParentInEffect(UUID parentId) {
    if (parentId != null
        && transactionRepository
            .findByIdIncludingDeletedForUpdate(parentId)
            .filter(parent -> parent.getVoidedAt() != null || parent.getDeletedAt() != null)
            .isPresent()) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT,
          "The transaction this one belongs to is removed; restore transaction "
              + parentId
              + " first.");
    }
  }

  private void requireNotRestored(List<Transaction> group) {
    for (Transaction row : group) {
      transactionRepository
          .findByRestoresTransactionId(row.getId())
          .ifPresent(
              copy -> {
                throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "This void was already restored as transaction " + copy.getId() + ".");
              });
    }
  }

  // An ordinary copy of a voided row, categorized like a new row. A transfer leg keeps what makes
  // it a transfer (its counterparty, and the internal-transfer flag of a two-sided entry or a
  // member's untracked-transfer confirmation); any other row starts unmatched, for detection to
  // pair again.
  private Transaction reinstate(
      Transaction original, UUID relatedTransactionId, AuthenticatedUserPrincipal actor) {
    Transaction copy = new Transaction();
    copy.setWorkspace(original.getWorkspace());
    copy.setAccount(original.getAccount());
    copy.setTransactionType(original.getTransactionType());
    copy.setBookingDate(original.getBookingDate());
    copy.setAmount(original.getAmount());
    copy.setCurrency(original.getCurrency());
    copy.setMerchantDescription(original.getMerchantDescription());
    copy.setNotes(original.getNotes());
    // Provenance and source data describe the same real-world transaction; the idempotency key
    // stays with the original, which an import's de-duplication still finds.
    copy.setSource(original.getSource());
    copy.setRawSourceData(original.getRawSourceData());
    copy.setFxRateToAccountCurrency(original.getFxRateToAccountCurrency());
    copy.setFxRateDate(original.getFxRateDate());
    copy.setFxRateEstimated(original.isFxRateEstimated());
    copy.setSecurityId(original.getSecurityId());
    copy.setQuantity(original.getQuantity());
    copy.setUnitPrice(original.getUnitPrice());
    copy.setFeeAmount(original.getFeeAmount());
    copy.setTradeDate(original.getTradeDate());
    copy.setSettlementDate(original.getSettlementDate());
    copy.setGrossAmount(original.getGrossAmount());
    copy.setTaxWithheldAmount(original.getTaxWithheldAmount());
    copy.setNetAmount(original.getNetAmount());
    copy.setRelatedTransactionId(relatedTransactionId);
    if (isTransferLeg(original)) {
      copy.setInternalTransfer(original.isInternalTransfer());
      copy.setCounterpartyAccountId(original.getCounterpartyAccountId());
    }
    copy.setRestoresTransactionId(original.getId());
    copy.setCreatedBy(actor.userId());
    Transaction saved = transactionRepository.saveAndFlush(copy);
    categorizationService.categorize(saved);
    categorizationService.carryOverride(original, saved, actor);
    return saved;
  }

  private ResponseStatusException expiredRestore(String removal) {
    return new ResponseStatusException(
        HttpStatus.CONFLICT,
        "This transaction was "
            + removal
            + " more than "
            + RESTORE_WINDOW_DAYS
            + " days ago and can no longer be restored.");
  }

  // Every account a restored row is on, once each: a transfer's other leg sits on another one.
  private void detectAfterRestore(List<Transaction> rows, LocalDate earliest, UUID changedBy) {
    Map<UUID, Account> accounts = new LinkedHashMap<>();
    rows.forEach(row -> accounts.putIfAbsent(row.getAccount().getId(), row.getAccount()));
    for (Account account : accounts.values()) {
      settlementDetectionService.detectAfterWrite(account, earliest);
      transferDetectionService.detectAfterWrite(account, earliest);
      reconciliationService.reconcileAfterLedgerChange(account, earliest, changedBy);
    }
  }

  /**
   * FR-LIF-006: the account's removed rows still restorable through {@link #restore} - soft-deleted
   * ({@code deletedAt} set) or voided ({@code voidedAt} set) within the window, and not yet
   * restored or corrected - most recently removed first.
   */
  @Transactional(readOnly = true)
  public List<TransactionResponse> listRestorable(
      UUID accountId, AuthenticatedUserPrincipal actor) {
    Account account = accountLookupService.findAccountOrThrow(accountId, actor);
    accessControlService.requireAccountAccess(actor, account, AccessLevelValues.READ);
    return transactionService.toResponses(
        transactionRepository.findRestorableByAccountIdSince(accountId, restoreWindowStart()));
  }

  /**
   * Locks an active transaction in the same order as removal: affected cards first, then the row.
   * Correction reuses this path before inspecting the row so it cannot invert the matching lock
   * order and deadlock with a concurrent detection/decision.
   */
  Transaction lockActiveTransaction(
      UUID accountId, UUID transactionId, AuthenticatedUserPrincipal actor) {
    Account account = requireEditable(accountId, actor);
    // Cards before rows, the order settlement matching takes them, so a concurrent match decision
    // and this lifecycle write queue behind each other instead of deadlocking.
    lockCards(account, transactionId);
    Transaction transaction =
        transactionRepository
            .findByIdForUpdate(transactionId)
            .filter(row -> row.getAccount().getId().equals(account.getId()))
            .orElseThrow(
                () -> accessControlService.denyAsNotFound(actor, "Transaction", transactionId));
    return transaction;
  }

  // US-07-06: a corrected row was soft-deleted by its correction, and its replacement now carries
  // the transaction. Restoring it - or its fee row or transfer leg, whose related row is the
  // corrected one - would count the transaction twice. The replacement is what to restore or
  // correct instead.
  private void requireNotCorrected(Transaction deleted, List<Transaction> restored) {
    Set<UUID> ids = new HashSet<>();
    restored.forEach(row -> ids.add(row.getId()));
    if (deleted.getRelatedTransactionId() != null) {
      ids.add(deleted.getRelatedTransactionId());
    }
    if (transactionRepository.existsCorrectionOfAny(ids)) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT,
          "This transaction was corrected and cannot be restored; its replacement is the current"
              + " entry.");
    }
  }

  // A removal may affect several accounts (a two-sided transfer). Reconcile each once, from the
  // earliest booking date changed on that account, so every snapshot at or after it is refreshed.
  private void reconcileAfterLedgerChanges(List<Transaction> rows, UUID changedBy) {
    Map<UUID, Account> accounts = new LinkedHashMap<>();
    Map<UUID, LocalDate> earliestByAccount = new LinkedHashMap<>();
    for (Transaction row : rows) {
      UUID accountId = row.getAccount().getId();
      accounts.putIfAbsent(accountId, row.getAccount());
      earliestByAccount.merge(
          accountId, row.getBookingDate(), (left, right) -> left.isBefore(right) ? left : right);
    }
    for (Map.Entry<UUID, Account> entry : accounts.entrySet()) {
      reconciliationService.reconcileAfterLedgerChange(
          entry.getValue(), earliestByAccount.get(entry.getKey()), changedBy);
    }
  }

  private static boolean isTransferLeg(Transaction row) {
    return TransferRecordingService.TRANSFER_TYPES.contains(row.getTransactionType());
  }

  // The other leg of a two-sided transfer sits on another account, which the member must be able to
  // edit too - the same rule as recording it.
  private void requireEditOnOtherAccounts(
      List<Transaction> rows, Account account, AuthenticatedUserPrincipal actor) {
    for (Transaction row : rows) {
      if (!row.getAccount().getId().equals(account.getId())) {
        accessControlService.requireAccountAccess(actor, row.getAccount(), AccessLevelValues.EDIT);
      }
    }
  }

  private Account requireEditable(UUID accountId, AuthenticatedUserPrincipal actor) {
    Account account = accountLookupService.findAccountOrThrow(accountId, actor);
    accessControlService.requireAccountAccess(actor, account, AccessLevelValues.EDIT);
    return account;
  }

  // Every card whose matching the row can take part in, not only those with a match on it yet: a
  // detection run already reading the row as an unmatched candidate holds its card's lock, and must
  // finish (or wait) before the row is hidden or voided, or it would link a match to a removed row.
  // Plus the cards of any existing match, which a former settlement source may still have. Sorted,
  // so two removals take them in the same order.
  private void lockCards(Account account, UUID transactionId) {
    lockCards(List.of(account), transactionId);
  }

  private void lockCards(List<Account> accounts, UUID transactionId) {
    SortedSet<UUID> cards = new TreeSet<>();
    accounts.forEach(account -> cards.addAll(settlementDetectionService.cardsAffectedBy(account)));
    settlementMatchRepository
        .findByTransactionId(transactionId)
        .forEach(match -> cards.add(match.getCardAccount().getId()));
    cards.forEach(settlementDetectionService::lockCard);
  }

  // A restore may bring back a transfer's other leg, on another account whose cards' matching it
  // takes part in too. That account is read before any row is locked (cards before rows); the
  // caller must be able to edit it anyway, which requireEditOnOtherAccounts checks again.
  private List<Account> accountsToLockForRestore(
      Account account, UUID transactionId, AuthenticatedUserPrincipal actor) {
    return transactionRepository
        .findTransferCounterpartyAccountId(
            transactionId, account.getId(), TransferRecordingService.TRANSFER_TYPES)
        .filter(other -> !other.equals(account.getId()))
        .map(other -> List.of(account, accountLookupService.findAccountOrThrow(other, actor)))
        .orElseGet(() -> List.of(account));
  }

  // A confirmed match flagged both legs an internal transfer; dissolving it makes the other leg an
  // ordinary payment or credit again (SettlementMatchService#reject does the same). A rejected
  // match is a member's decision and is kept whichever way the row goes (see the class comment).
  private void dissolveMatches(Transaction row, Set<UUID> unmatched) {
    for (SettlementMatch match : settlementMatchRepository.findByTransactionId(row.getId())) {
      if (SettlementMatchValues.REJECTED.equals(match.getStatus())) {
        continue;
      }
      if (SettlementMatchValues.CONFIRMED.equals(match.getStatus())) {
        settlementDetectionService.clearFlags(match);
      }
      addOtherLeg(match, row, unmatched);
      settlementMatchRepository.delete(match);
    }
    settlementMatchRepository.flush();
  }

  private static void addOtherLeg(SettlementMatch match, Transaction row, Set<UUID> into) {
    Transaction payment = match.getPaymentTransaction();
    Transaction card = match.getCardTransaction();
    Transaction other = row.getId().equals(payment.getId()) ? card : payment;
    if (other != null) {
      into.add(other.getId());
    }
  }

  /**
   * FR-LIF-002: the same type, account, currency, rate and security, with the amount and every
   * signed figure negated - so a position's quantity sum and an account's balance both net to zero
   * - dated to the void, or to the original's own date when that lies later (V36 keeps a trade date
   * on or before its booking date). No category (US-08-01): it is excluded from figures.
   */
  private Transaction reversalOf(Transaction original, UUID actorUserId) {
    Transaction reversal = new Transaction();
    reversal.setWorkspace(original.getWorkspace());
    reversal.setAccount(original.getAccount());
    reversal.setTransactionType(original.getTransactionType());
    LocalDate today = businessDateService.today();
    reversal.setBookingDate(
        original.getBookingDate().isAfter(today) ? original.getBookingDate() : today);
    reversal.setAmount(original.getAmount().negate());
    reversal.setCurrency(original.getCurrency());
    reversal.setFxRateToAccountCurrency(original.getFxRateToAccountCurrency());
    reversal.setFxRateDate(original.getFxRateDate());
    reversal.setFxRateEstimated(original.isFxRateEstimated());
    reversal.setMerchantDescription(original.getMerchantDescription());
    reversal.setSecurityId(original.getSecurityId());
    reversal.setQuantity(negate(original.getQuantity()));
    reversal.setUnitPrice(original.getUnitPrice());
    reversal.setFeeAmount(original.getFeeAmount());
    reversal.setTradeDate(original.getTradeDate());
    reversal.setSettlementDate(original.getSettlementDate());
    reversal.setGrossAmount(negate(original.getGrossAmount()));
    reversal.setTaxWithheldAmount(negate(original.getTaxWithheldAmount()));
    reversal.setNetAmount(negate(original.getNetAmount()));
    // The reversal carries the original's provenance: an imported row's correction stays T2.
    reversal.setSource(original.getSource());
    reversal.setReplacesTransactionId(original.getId());
    reversal.setCreatedBy(actorUserId);
    return reversal;
  }

  private static BigDecimal negate(BigDecimal value) {
    return value == null ? null : value.negate();
  }

  private static String requireReason(String reason) {
    String trimmed = reason == null ? "" : reason.strip();
    if (trimmed.isEmpty()) {
      throw new ResponseStatusException(
          HttpStatus.UNPROCESSABLE_CONTENT,
          "An imported transaction is voided, not deleted: give a reason (FR-LIF-002).");
    }
    if (trimmed.length() > MAX_REASON_LENGTH) {
      throw new ResponseStatusException(
          HttpStatus.UNPROCESSABLE_CONTENT,
          "reason must be at most " + MAX_REASON_LENGTH + " characters.");
    }
    return trimmed;
  }

  private OffsetDateTime restoreWindowStart() {
    return OffsetDateTime.now(clock).minus(Duration.ofDays(RESTORE_WINDOW_DAYS));
  }
}
