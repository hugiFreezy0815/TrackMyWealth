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
import java.util.HashSet;
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
 *
 * <p><b>Stale duplicates (#287).</b> A row the preview found duplicate keeps its ledger row while
 * that row is in effect. Once it is not - a rollback deleted it and cleared the pointer, or it was
 * voided or soft-deleted - the row is checked again with the others: if the ledger holds the
 * booking as another row, the row points there; if not, the row is new and included (it was only
 * left out for being a duplicate) and is imported. As V69 says, a voided row is no duplicate.
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

    // Every readable row, in file order. A duplicate whose booking is still in effect keeps it, so
    // an identical row after it stays new (the multiplicity rule); every other row is checked again
    // against the rest of the ledger, which may have gained or lost the booking since the preview.
    List<ImportRowRaw> readable =
        rowRepository.findByImportBatchIdAndParseStatusInOrderByRowNumber(
            batch.getId(), List.of(ImportBatchValues.ROW_PARSED, ImportBatchValues.ROW_DUPLICATE));
    Set<UUID> held = rowRepository.findDuplicateRowIdsInEffect(batch.getId());
    Set<UUID> reserved = new HashSet<>();
    List<ParsedImportRow> recheck = new ArrayList<>();
    for (ImportRowRaw row : readable) {
      if (held.contains(row.getId())) {
        reserved.add(row.getDuplicateOfTransactionId());
      } else {
        recheck.add(
            ParsedImportRow.parsed(
                row.getRowNumber(),
                batchService.stringMap(row.getRawData()),
                batchService.canonical(row)));
      }
    }
    Map<Integer, UUID> nowDuplicates =
        duplicateService.findDuplicates(account.getId(), batch.getSourceKind(), recheck, reserved);

    // #287: a duplicate whose booking the ledger lost (a rollback, a removal) is checked again.
    // Held as another row, it points there; gone, it is new and included, as it was only left out
    // for being a duplicate (product owner, 2026-10-13).
    Set<UUID> becameNew = new HashSet<>();
    List<UUID> repointed = new ArrayList<>();
    List<UUID> repointedTo = new ArrayList<>();
    List<ImportRowRaw> included = new ArrayList<>();
    for (ImportRowRaw row : readable) {
      UUID now = nowDuplicates.get(row.getRowNumber());
      boolean stale =
          ImportBatchValues.ROW_DUPLICATE.equals(row.getParseStatus())
              && !held.contains(row.getId());
      if (stale && now == null) {
        becameNew.add(row.getId());
      } else if (stale && !now.equals(row.getDuplicateOfTransactionId())) {
        repointed.add(row.getId());
        repointedTo.add(now);
      }
      if (row.isIncluded() || becameNew.contains(row.getId())) {
        included.add(row);
      }
    }
    // A duplicate the member forced in is a second booking; one whose reference the ledger holds
    // already goes in without it.
    Set<String> takenReferences =
        duplicateService.takenReferences(
            account.getId(),
            batch.getSourceKind(),
            included.stream()
                .filter(row -> isDuplicate(row, becameNew))
                .map(row -> batchService.canonical(row).externalId())
                .filter(Objects::nonNull)
                .toList());

    List<UUID> becameDuplicates = new ArrayList<>();
    List<UUID> duplicateOf = new ArrayList<>();
    List<ImportRowRaw> fresh = new ArrayList<>();
    List<ImportLedgerRow> ledger = new ArrayList<>();
    for (ImportRowRaw row : included) {
      // A row new in the preview that the ledger has gained meanwhile; a row that just became new
      // has no match by definition.
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
      if (isDuplicate(row, becameNew)
          && canonical.externalId() != null
          && takenReferences.contains(canonical.externalId())) {
        canonical = ImportLedgerRowService.withoutReference(canonical);
      }
      ledger.add(ledgerRows.toLedgerRow(canonical, batchService.stringMap(row.getRawData())));
      fresh.add(row);
    }
    if (!becameDuplicates.isEmpty()) {
      rowBatchRepository.markDuplicates(becameDuplicates, duplicateOf);
    }
    if (!becameNew.isEmpty()) {
      rowBatchRepository.markNew(List.copyOf(becameNew));
    }
    if (!repointed.isEmpty()) {
      rowBatchRepository.repointDuplicates(repointed, repointedTo);
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
    batch.setDuplicateRowCount(
        batch.getDuplicateRowCount() + becameDuplicates.size() - becameNew.size());
    batchRepository.saveAndFlush(batch);
    if (LOG.isInfoEnabled()) {
      LOG.info(
          "Committed import batch {}: {} transactions, {} rows found duplicate at commit,"
              + " {} duplicates found new",
          batch.getId(),
          recorded.size(),
          becameDuplicates.size(),
          becameNew.size());
    }
    return batchService.summary(batch, account, List.of());
  }

  // Whether the row goes into the commit as a duplicate (forced in or not): one the preview found,
  // unless the commit found it new.
  private static boolean isDuplicate(ImportRowRaw row, Set<UUID> becameNew) {
    return ImportBatchValues.ROW_DUPLICATE.equals(row.getParseStatus())
        && !becameNew.contains(row.getId());
  }
}
