package com.trackmywealth.backend.service;

import com.trackmywealth.backend.dto.CanonicalImportRow;
import com.trackmywealth.backend.dto.ImportBatchResponse;
import com.trackmywealth.backend.dto.ImportBatchValues;
import com.trackmywealth.backend.dto.ImportLedgerRow;
import com.trackmywealth.backend.dto.ParsedImportRow;
import com.trackmywealth.backend.entity.Account;
import com.trackmywealth.backend.entity.ImportBatch;
import com.trackmywealth.backend.entity.ImportRowRaw;
import com.trackmywealth.backend.entity.Transaction;
import com.trackmywealth.backend.repository.ImportBatchRepository;
import com.trackmywealth.backend.repository.ImportRowRawBatchRepository;
import com.trackmywealth.backend.repository.ImportRowRawRepository;
import com.trackmywealth.backend.repository.WorkspaceRepository;
import com.trackmywealth.backend.security.AuthenticatedUserPrincipal;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Commits a previewed import batch (US-07-04, FR-IMP-004/005): its included rows become
 * transactions in one database transaction, through the manual entry's own rules ({@link
 * TransactionService#recordImported}), and the batch becomes {@code COMMITTED}. Nothing is written
 * when the ledger refuses a row; the batch then stays {@code PARSED}.
 *
 * <p><b>Concurrency.</b> Commits into one account queue behind an advisory lock on that account,
 * taken before anything else, then the batch row's lock: two batches of the same file committed at
 * the same time run one after the other, and the second one's duplicate check sees the first one's
 * rows. That check runs again under the lock for every included row that was new in the preview: a
 * row the ledger has gained meanwhile is not inserted but recorded as the {@code DUPLICATE} it now
 * is. A row the member forced in stays forced in. The unique {@code (account, source, external_id)}
 * index stays the backstop for rows with a reference. After the inserts, the ledger takes the locks
 * of a manual write (cards in id order, the workspace's transfer lock, the snapshot). A commit of a
 * batch that is already committed - a retry - is a 409 {@code IMPORT_BATCH_STATE}, never a second
 * import (FR-API-006).
 */
@Service
public class ImportCommitService {

  private static final Logger LOG = LoggerFactory.getLogger(ImportCommitService.class);
  // Per account; a rollback (ImportRollbackService) takes it too, before the batch row like here.
  static final String LOCK_PREFIX = "import-commit:";

  private final ImportBatchService batchService;
  private final ImportBatchRepository batchRepository;
  private final ImportRowRawRepository rowRepository;
  private final ImportRowRawBatchRepository rowBatchRepository;
  private final ImportDuplicateService duplicateService;
  private final ImportLedgerRowService ledgerRows;
  private final TransactionService transactionService;
  private final VersionPreconditionService versionPreconditionService;
  private final WorkspaceRepository workspaceRepository;
  private final Clock clock;

  public ImportCommitService(
      ImportBatchService batchService,
      ImportBatchRepository batchRepository,
      ImportRowRawRepository rowRepository,
      ImportRowRawBatchRepository rowBatchRepository,
      ImportDuplicateService duplicateService,
      ImportLedgerRowService ledgerRows,
      TransactionService transactionService,
      VersionPreconditionService versionPreconditionService,
      WorkspaceRepository workspaceRepository,
      Clock clock) {
    this.batchService = batchService;
    this.batchRepository = batchRepository;
    this.rowRepository = rowRepository;
    this.rowBatchRepository = rowBatchRepository;
    this.duplicateService = duplicateService;
    this.ledgerRows = ledgerRows;
    this.transactionService = transactionService;
    this.versionPreconditionService = versionPreconditionService;
    this.workspaceRepository = workspaceRepository;
    this.clock = clock;
  }

  /**
   * {@code PARSED -> COMMITTED}: inserts the included rows and returns the batch's summary.
   *
   * @throws com.trackmywealth.backend.error.ApiException 409 {@code IMPORT_BATCH_STATE} unless the
   *     batch is {@code PARSED}; 412/428 for a stale or missing {@code If-Match}
   */
  @Transactional
  public ImportBatchResponse commit(
      UUID accountId, UUID batchId, Integer expectedVersion, AuthenticatedUserPrincipal actor) {
    Account account = batchService.requireImportableAccount(accountId, actor);
    workspaceRepository.lockAdvisory(LOCK_PREFIX + account.getId());
    ImportBatch batch = batchService.lockBatch(account, batchId, actor);
    // The state before the version: a retried commit learns that it already happened.
    ImportBatchService.requireStatus(batch, ImportBatchValues.PARSED);
    versionPreconditionService.requireCurrent(
        expectedVersion, batch.getVersion(), ImportBatchService.VERSIONED_RESOURCE);

    // Every readable row, in file order, as the preview checked them: a duplicate left out still
    // takes its ledger row, so an identical row after it stays new (the multiplicity rule).
    List<ImportRowRaw> readable =
        rowRepository.findByImportBatchIdAndParseStatusInOrderByRowNumber(
            batch.getId(), List.of(ImportBatchValues.ROW_PARSED, ImportBatchValues.ROW_DUPLICATE));
    List<ImportRowRaw> included = readable.stream().filter(ImportRowRaw::isIncluded).toList();
    List<ImportRowRaw> fresh = new ArrayList<>();
    List<ParsedImportRow> recheck = new ArrayList<>();
    for (ImportRowRaw row : readable) {
      recheck.add(
          ParsedImportRow.parsed(
              row.getRowNumber(),
              batchService.stringMap(row.getRawData()),
              batchService.canonical(row)));
    }
    Map<Integer, UUID> nowDuplicates =
        duplicateService.findDuplicates(account.getId(), batch.getSourceKind(), recheck);
    // A duplicate the member forced in is a second booking; one whose reference the ledger holds
    // already goes in without it.
    Set<String> takenReferences =
        duplicateService.takenReferences(
            account.getId(),
            batch.getSourceKind(),
            included.stream()
                .filter(row -> ImportBatchValues.ROW_DUPLICATE.equals(row.getParseStatus()))
                .map(row -> batchService.canonical(row).externalId())
                .filter(Objects::nonNull)
                .toList());

    List<UUID> becameDuplicates = new ArrayList<>();
    List<UUID> duplicateOf = new ArrayList<>();
    List<ImportLedgerRow> ledger = new ArrayList<>();
    for (ImportRowRaw row : included) {
      UUID duplicate =
          ImportBatchValues.ROW_PARSED.equals(row.getParseStatus())
              ? nowDuplicates.get(row.getRowNumber())
              : null;
      if (duplicate != null) {
        becameDuplicates.add(row.getId());
        duplicateOf.add(duplicate);
        continue;
      }
      CanonicalImportRow canonical = batchService.canonical(row);
      if (canonical.externalId() != null && takenReferences.contains(canonical.externalId())) {
        canonical = ImportLedgerRowService.withoutReference(canonical);
      }
      ledger.add(ledgerRows.toLedgerRow(canonical, batchService.stringMap(row.getRawData())));
      fresh.add(row);
    }
    if (!becameDuplicates.isEmpty()) {
      rowBatchRepository.markDuplicates(becameDuplicates, duplicateOf);
    }

    List<Transaction> recorded =
        transactionService.recordImported(
            account, ledger, batch.getId(), batch.getSourceKind(), actor);
    rowBatchRepository.linkTransactions(
        fresh.stream().map(ImportRowRaw::getId).toList(),
        recorded.stream().map(Transaction::getId).toList());

    batch.setStatus(ImportBatchValues.COMMITTED);
    batch.setCommittedAt(OffsetDateTime.now(clock));
    batch.setImportedRowCount(recorded.size());
    batch.setDuplicateRowCount(batch.getDuplicateRowCount() + becameDuplicates.size());
    batchRepository.saveAndFlush(batch);
    if (LOG.isInfoEnabled()) {
      LOG.info(
          "Committed import batch {}: {} transactions, {} rows found duplicate at commit",
          batch.getId(),
          recorded.size(),
          becameDuplicates.size());
    }
    return batchService.summary(batch, account, List.of());
  }
}
