package com.trackmywealth.backend.service;

import com.trackmywealth.backend.dto.CanonicalImportRow;
import com.trackmywealth.backend.dto.ImportBatchResponse;
import com.trackmywealth.backend.dto.ImportBatchValues;
import com.trackmywealth.backend.dto.ImportLedgerRow;
import com.trackmywealth.backend.dto.ImportRecheck;
import com.trackmywealth.backend.dto.ImportRowRecheck;
import com.trackmywealth.backend.entity.Account;
import com.trackmywealth.backend.entity.ImportBatch;
import com.trackmywealth.backend.entity.ImportRowRaw;
import com.trackmywealth.backend.entity.Transaction;
import com.trackmywealth.backend.repository.ImportBatchRepository;
import com.trackmywealth.backend.repository.ImportRowRawBatchRepository;
import com.trackmywealth.backend.security.AuthenticatedUserPrincipal;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
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
 * <p><b>Stale duplicates (#287).</b> A row the preview found duplicate keeps its booking while the
 * ledger holds it: the row it points at while that is in effect, or what replaced that row since -
 * a correction's replacement or a restored void's copy in the same account, which the row then
 * points at; a booking a correction moved to another account is gone from this one. Nothing else
 * may match that ledger row. Once the booking is gone - a rollback deleted the row and cleared the
 * pointer, or it was voided or soft-deleted for good - the row is checked again with the others,
 * under the duplicate rules of a fresh import: if the ledger holds the booking as another row, the
 * row points there; if not, the row is new and included (it was only left out for being a
 * duplicate) and is imported. As V69 says, a voided row is no duplicate; a soft-deleted row still
 * reserves its reference, so a row matching it by reference stays its duplicate. Removals,
 * corrections and restores take this commit's lock on every account they reach before anything else
 * ({@code TransactionRemovalService}), so none lands between the check and the commit's end. The
 * check is {@link ImportRecheckService}'s, the same one the preview's rows and counts report
 * (#308), so the commit does what the member saw.
 */
@Service
public class ImportCommitService {

  private static final Logger LOG = LoggerFactory.getLogger(ImportCommitService.class);

  private final ImportBatchService batchService;
  private final ImportBatchRepository batchRepository;
  private final ImportRowRawBatchRepository rowBatchRepository;
  private final ImportRecheckService recheckService;
  private final ImportDuplicateService duplicateService;
  private final ImportLedgerRowService ledgerRows;
  private final TransactionService transactionService;
  private final VersionPreconditionService versionPreconditionService;
  private final ImportLockService importLockService;
  private final Clock clock;

  public ImportCommitService(
      ImportBatchService batchService,
      ImportBatchRepository batchRepository,
      ImportRowRawBatchRepository rowBatchRepository,
      ImportRecheckService recheckService,
      ImportDuplicateService duplicateService,
      ImportLedgerRowService ledgerRows,
      TransactionService transactionService,
      VersionPreconditionService versionPreconditionService,
      ImportLockService importLockService,
      Clock clock) {
    this.batchService = batchService;
    this.batchRepository = batchRepository;
    this.rowBatchRepository = rowBatchRepository;
    this.recheckService = recheckService;
    this.duplicateService = duplicateService;
    this.ledgerRows = ledgerRows;
    this.transactionService = transactionService;
    this.versionPreconditionService = versionPreconditionService;
    this.importLockService = importLockService;
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
    importLockService.lockAccount(account.getId());
    ImportBatch batch = batchService.lockBatch(account, batchId, actor);
    // The state before the version: a retried commit learns that it already happened.
    ImportBatchService.requireStatus(batch, ImportBatchValues.PARSED);
    versionPreconditionService.requireCurrent(
        expectedVersion, batch.getVersion(), ImportBatchService.VERSIONED_RESOURCE);

    // Every readable row, in file order, and what the ledger makes of it now (#287): a duplicate
    // whose booking moved points at the row holding it, one whose booking is gone is new and
    // included, an included new row the ledger has gained is a duplicate.
    List<ImportRowRaw> readable = recheckService.readableRows(batch);
    ImportRecheck recheck = recheckService.recheck(batch, readable);
    List<ImportRowRaw> included = new ArrayList<>();
    for (ImportRowRaw row : readable) {
      ImportRowRecheck rechecked = recheck.of(row.getId());
      if (rechecked == null ? row.isIncluded() : rechecked.included()) {
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
                .filter(row -> isDuplicate(row, recheck))
                .map(row -> batchService.canonical(row).externalId())
                .filter(Objects::nonNull)
                .toList());

    List<ImportLedgerRow> ledger = new ArrayList<>();
    for (ImportRowRaw row : included) {
      CanonicalImportRow canonical = batchService.canonical(row);
      if (isDuplicate(row, recheck)
          && canonical.externalId() != null
          && takenReferences.contains(canonical.externalId())) {
        canonical = ImportLedgerRowService.withoutReference(canonical);
      }
      ledger.add(ledgerRows.toLedgerRow(canonical, batchService.stringMap(row.getRawData())));
    }
    recheckService.apply(batch, recheck);

    List<Transaction> recorded =
        transactionService.recordImported(
            account, ledger, batch.getId(), batch.getSourceKind(), actor);
    rowBatchRepository.linkTransactions(
        included.stream().map(ImportRowRaw::getId).toList(),
        recorded.stream().map(Transaction::getId).toList());

    batch.setStatus(ImportBatchValues.COMMITTED);
    batch.setCommittedAt(OffsetDateTime.now(clock));
    batch.setImportedRowCount(recorded.size());
    batchRepository.saveAndFlush(batch);
    if (LOG.isInfoEnabled()) {
      LOG.info(
          "Committed import batch {}: {} transactions, {} rows found duplicate at commit,"
              + " {} duplicates found new",
          batch.getId(),
          recorded.size(),
          count(recheck, ImportBatchValues.RECHECK_DUPLICATE),
          count(recheck, ImportBatchValues.RECHECK_NEW));
    }
    return batchService.summary(batch, account, List.of());
  }

  // Whether the row goes into the commit as a duplicate (forced in, or repointed and forced in):
  // the
  // re-checked status, or else the stored one.
  private static boolean isDuplicate(ImportRowRaw row, ImportRecheck recheck) {
    ImportRowRecheck rechecked = recheck.of(row.getId());
    String status = rechecked == null ? row.getParseStatus() : rechecked.status();
    return ImportBatchValues.ROW_DUPLICATE.equals(status);
  }

  private static long count(ImportRecheck recheck, String outcome) {
    return recheck.rows().values().stream().filter(row -> outcome.equals(row.recheck())).count();
  }
}
