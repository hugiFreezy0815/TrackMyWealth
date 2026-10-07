package com.trackmywealth.backend.service;

import com.trackmywealth.backend.dto.ImportBatchValues;
import com.trackmywealth.backend.dto.ImportRollbackModifiedResponse;
import com.trackmywealth.backend.dto.ImportRollbackResponse;
import com.trackmywealth.backend.dto.ImportRollbackValues;
import com.trackmywealth.backend.dto.SettlementMatchValues;
import com.trackmywealth.backend.entity.Account;
import com.trackmywealth.backend.entity.ImportBatch;
import com.trackmywealth.backend.entity.SettlementMatch;
import com.trackmywealth.backend.entity.Transaction;
import com.trackmywealth.backend.repository.ImportBatchRepository;
import com.trackmywealth.backend.repository.ImportRollbackRepository;
import com.trackmywealth.backend.repository.SettlementMatchRepository;
import com.trackmywealth.backend.repository.TransactionRepository;
import com.trackmywealth.backend.repository.WorkspaceRepository;
import com.trackmywealth.backend.security.AuthenticatedUserPrincipal;
import jakarta.persistence.EntityManager;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * US-07-05 (FR-IMP-005, FR-LIF-002c/010/011/012): rolls back a committed import batch as a whole,
 * the only way imported rows are removed in bulk. The system decides how, never the member:
 *
 * <ul>
 *   <li><b>Unmodified</b> - nobody worked on any of its transactions: they are deleted, with the
 *       matches they are a leg of and their categorization log, and the batch's rows are unlinked
 *       from them. The batch becomes {@code ROLLED_BACK}; re-importing the file is a fresh import.
 *       This is the one hard delete of a transaction the schema allows (V68).
 *   <li><b>Modified</b> - someone worked on at least one ({@link ImportRollbackValues#CRITERIA}):
 *       every still active transaction is voided through the T2 path ({@link
 *       TransactionRemovalService#voidRows}: reversing rows, matches dissolved, a purchase's FEE
 *       row with it), rows voided or deleted before are left alone, and the batch becomes {@code
 *       VOIDED}. The answer names each modified transaction and why.
 * </ul>
 *
 * <p>Whether the batch is modified is computed here, under the locks, one query per criterion;
 * {@code import_batch.contains_modified_records} is only set afterwards, as a record. Either way
 * everything happens in one database transaction (FR-LIF-012), the batch, its rows and its file
 * stay, the batch records who rolled it back, when and why, and every affected account is
 * reconciled again (US-25-02, T3).
 *
 * <p><b>Locks.</b> The account's import advisory lock first, as a commit takes it, then the batch
 * row: a rollback and a commit of the same account queue behind each other instead of deadlocking,
 * and a second rollback of the batch finds it no longer {@code COMMITTED} (409). Then, as a single
 * removal does, every card whose matching the rows take part in, in id order, and the batch's
 * transactions in id order.
 */
@Service
public class ImportRollbackService {

  private static final Logger LOG = LoggerFactory.getLogger(ImportRollbackService.class);

  private final ImportBatchService batchService;
  private final ImportBatchRepository batchRepository;
  private final ImportRollbackRepository rollbackRepository;
  private final TransactionRepository transactionRepository;
  private final SettlementMatchRepository settlementMatchRepository;
  private final SettlementDetectionService settlementDetectionService;
  private final TransactionRemovalService removalService;
  private final ReconciliationService reconciliationService;
  private final VersionPreconditionService versionPreconditionService;
  private final WorkspaceRepository workspaceRepository;
  private final EntityManager entityManager;
  private final Clock clock;

  public ImportRollbackService(
      ImportBatchService batchService,
      ImportBatchRepository batchRepository,
      ImportRollbackRepository rollbackRepository,
      TransactionRepository transactionRepository,
      SettlementMatchRepository settlementMatchRepository,
      SettlementDetectionService settlementDetectionService,
      TransactionRemovalService removalService,
      ReconciliationService reconciliationService,
      VersionPreconditionService versionPreconditionService,
      WorkspaceRepository workspaceRepository,
      EntityManager entityManager,
      Clock clock) {
    this.batchService = batchService;
    this.batchRepository = batchRepository;
    this.rollbackRepository = rollbackRepository;
    this.transactionRepository = transactionRepository;
    this.settlementMatchRepository = settlementMatchRepository;
    this.settlementDetectionService = settlementDetectionService;
    this.removalService = removalService;
    this.reconciliationService = reconciliationService;
    this.versionPreconditionService = versionPreconditionService;
    this.workspaceRepository = workspaceRepository;
    this.entityManager = entityManager;
    this.clock = clock;
  }

  /**
   * {@code COMMITTED -> ROLLED_BACK} or {@code VOIDED}, whichever applies (see the class comment).
   * Needs the same access as an import: {@code EDIT} on an active account.
   *
   * @param reason why, already validated (1 to 500 characters); recorded on the batch and the void
   *     reason of every voided row
   * @throws com.trackmywealth.backend.error.ApiException 409 {@code IMPORT_BATCH_STATE} unless the
   *     batch is {@code COMMITTED}; 412/428 for a stale or missing {@code If-Match}
   */
  @Transactional
  public ImportRollbackResponse rollback(
      UUID accountId,
      UUID batchId,
      String reason,
      Integer expectedVersion,
      AuthenticatedUserPrincipal actor) {
    Account account = batchService.requireImportableAccount(accountId, actor);
    workspaceRepository.lockAdvisory(ImportCommitService.LOCK_PREFIX + account.getId());
    ImportBatch batch = batchService.lockBatch(account, batchId, actor);
    // The state before the version: a retried rollback learns that it already happened.
    ImportBatchService.requireStatus(batch, ImportBatchValues.COMMITTED);
    versionPreconditionService.requireCurrent(
        expectedVersion, batch.getVersion(), ImportBatchService.VERSIONED_RESOURCE);

    List<UUID> transactionIds = rollbackRepository.findTransactionIds(batch.getId());
    lockCards(account, transactionIds);
    rollbackRepository.lockTransactions(batch.getId());
    Map<UUID, List<String>> modified =
        modifiedInBatchOrder(rollbackRepository.findModified(batch.getId()), transactionIds);

    String why = reason.strip();
    ImportRollbackResponse response =
        modified.isEmpty()
            ? delete(account, batch, transactionIds, why, actor)
            : voidAll(account, batch, transactionIds, modified, why, actor);
    if (LOG.isInfoEnabled()) {
      LOG.info(
          "Rolled back import batch {} by {}: {} transactions deleted, {} voided ({} modified)",
          batch.getId(),
          response.rollback(),
          response.deletedTransactionCount(),
          response.voidedTransactionIds().size(),
          response.modified().size());
    }
    return response;
  }

  // FR-LIF-010: nobody worked on the batch. Every match a row is a leg of was proposed or decided
  // by the system; a confirmed one flagged its legs a transfer, which the other leg stops being.
  private ImportRollbackResponse delete(
      Account account,
      ImportBatch batch,
      List<UUID> transactionIds,
      String reason,
      AuthenticatedUserPrincipal actor) {
    SortedSet<UUID> unmatched = new TreeSet<>();
    if (!transactionIds.isEmpty()) {
      Set<UUID> batchRows = new HashSet<>(transactionIds);
      List<SettlementMatch> matches = settlementMatchRepository.findLoadableTouching(batchRows);
      for (SettlementMatch match : matches) {
        if (SettlementMatchValues.CONFIRMED.equals(match.getStatus())) {
          settlementDetectionService.clearFlags(match);
        }
        if (!SettlementMatchValues.REJECTED.equals(match.getStatus())) {
          legsOf(match).stream()
              .map(Transaction::getId)
              .filter(id -> !batchRows.contains(id))
              .forEach(unmatched::add);
        }
      }
      // The cleared flags are written before the rows go; the deleted rows and their matches must
      // not linger in the session, where a later flush could still try to write them.
      transactionRepository.flush();
      for (SettlementMatch match : matches) {
        entityManager.detach(match);
        legsOf(match).stream()
            .filter(leg -> batchRows.contains(leg.getId()))
            .forEach(entityManager::detach);
      }
    }
    LocalDate earliest = rollbackRepository.findEarliestBookingDate(batch.getId());
    int deleted = rollbackRepository.deleteTransactions(batch.getId());
    end(batch, ImportBatchValues.ROLLED_BACK, reason, actor);
    if (earliest != null) {
      reconciliationService.reconcileAfterLedgerChange(account, earliest, actor.userId());
    }
    return new ImportRollbackResponse(
        batchService.summary(batch, account, List.of()),
        ImportRollbackValues.HARD_DELETE,
        deleted,
        List.of(),
        List.of(),
        List.of(),
        List.copyOf(unmatched));
  }

  // FR-LIF-011: voids every row still in effect the way a member's removal would, each with the
  // rows that go with it. A row voided, deleted or corrected before keeps what was done to it.
  private ImportRollbackResponse voidAll(
      Account account,
      ImportBatch batch,
      List<UUID> transactionIds,
      Map<UUID, List<String>> modified,
      String reason,
      AuthenticatedUserPrincipal actor) {
    // In the order the rows were locked (the database's id order). Soft-deleted rows are not found
    // (the entity's restriction); voided ones are skipped.
    Map<UUID, Transaction> found = new HashMap<>();
    transactionRepository.findAllById(transactionIds).forEach(row -> found.put(row.getId(), row));
    List<Transaction> active = new ArrayList<>();
    for (UUID id : transactionIds) {
      Transaction row = found.get(id);
      if (row != null && row.getVoidedAt() == null) {
        active.add(row);
      }
    }
    List<Transaction> voided = removalService.removalGroupsOf(active);
    removalService.requireEditOnOtherAccounts(voided, account, actor);
    SortedSet<UUID> unmatched = new TreeSet<>();
    List<Transaction> reversals =
        removalService.voidRows(voided, reason, actor.userId(), unmatched);
    batch.setContainsModifiedRecords(true);
    end(batch, ImportBatchValues.VOIDED, reason, actor);

    List<ImportRollbackModifiedResponse> reasons = new ArrayList<>();
    modified.forEach(
        (transactionId, criteria) ->
            reasons.add(new ImportRollbackModifiedResponse(transactionId, criteria)));
    return new ImportRollbackResponse(
        batchService.summary(batch, account, List.of()),
        ImportRollbackValues.VOID,
        0,
        reasons,
        idsOf(voided),
        idsOf(reversals),
        List.copyOf(unmatched));
  }

  private void end(
      ImportBatch batch, String status, String reason, AuthenticatedUserPrincipal actor) {
    batch.setStatus(status);
    batch.recordRollback(OffsetDateTime.now(clock), actor.userId(), reason);
    batchRepository.saveAndFlush(batch);
  }

  /**
   * Each modified transaction, in {@code batchOrder}, with every criterion that applies to it in
   * the order of {@code byCriterion} (the repository answers in {@link
   * ImportRollbackValues#CRITERIA} order). A transaction no criterion names is not modified and
   * absent.
   */
  static Map<UUID, List<String>> modifiedInBatchOrder(
      Map<String, List<UUID>> byCriterion, List<UUID> batchOrder) {
    Map<UUID, List<String>> criteriaById = new HashMap<>();
    byCriterion.forEach(
        (criterion, ids) ->
            ids.forEach(
                id -> criteriaById.computeIfAbsent(id, key -> new ArrayList<>()).add(criterion)));
    Map<UUID, List<String>> modified = new LinkedHashMap<>();
    for (UUID id : batchOrder) {
      List<String> criteria = criteriaById.get(id);
      if (criteria != null) {
        modified.put(id, List.copyOf(criteria));
      }
    }
    return modified;
  }

  // As a single removal locks them (TransactionRemovalService#lockCards): every card whose
  // matching a row of the account can take part in, plus the card of any match a batch row is a
  // leg of, in id order - before the rows, the order settlement matching takes them.
  private void lockCards(Account account, List<UUID> transactionIds) {
    SortedSet<UUID> cards = new TreeSet<>(settlementDetectionService.cardsAffectedBy(account));
    if (!transactionIds.isEmpty()) {
      cards.addAll(settlementMatchRepository.findCardAccountIdsTouching(transactionIds));
    }
    cards.forEach(settlementDetectionService::lockCard);
  }

  private static List<UUID> idsOf(List<Transaction> rows) {
    return rows.stream().map(Transaction::getId).toList();
  }

  private static List<Transaction> legsOf(SettlementMatch match) {
    List<Transaction> legs = new ArrayList<>(2);
    legs.add(match.getPaymentTransaction());
    if (match.getCardTransaction() != null) {
      legs.add(match.getCardTransaction());
    }
    return legs;
  }
}
