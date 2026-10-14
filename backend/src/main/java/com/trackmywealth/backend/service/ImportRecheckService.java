package com.trackmywealth.backend.service;

import com.trackmywealth.backend.dto.ImportBatchValues;
import com.trackmywealth.backend.dto.ImportDuplicateHold;
import com.trackmywealth.backend.dto.ImportRecheck;
import com.trackmywealth.backend.dto.ImportRowRecheck;
import com.trackmywealth.backend.dto.ParsedImportRow;
import com.trackmywealth.backend.entity.ImportBatch;
import com.trackmywealth.backend.entity.ImportRowRaw;
import com.trackmywealth.backend.repository.ImportRowRawBatchRepository;
import com.trackmywealth.backend.repository.ImportRowRawRepository;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

/**
 * Checks a {@code PARSED} preview's readable rows against the ledger again (#287, #308): the ledger
 * may have lost or gained a booking since the preview was parsed. One implementation for the commit
 * ({@link ImportCommitService}), which applies the result, and for every read of the preview
 * ({@link ImportBatchService}: its rows and counts), which reports it, so what a member sees is
 * what the commit does.
 *
 * <ul>
 *   <li>A duplicate whose booking the ledger still holds keeps it: the row it points at while that
 *       is in effect, or what a correction or restored void replaced it with ({@link
 *       ImportRowRawRepository#findDuplicateHolds}); in the second case it is re-pointed there. An
 *       identical row after it stays new (the multiplicity rule).
 *   <li>Every other row is checked again as a fresh import would check it. A duplicate whose
 *       booking the ledger lost (a rollback, a removal) is re-pointed at another row that holds it,
 *       or else becomes new and included, as it was only left out for being a duplicate (product
 *       owner, 2026-10-13); a member's earlier exclusion of it does not survive (#308).
 *   <li>A new, included row whose booking the ledger has gained since becomes an excluded
 *       duplicate. An excluded new row is not imported anyway and stays as it is.
 * </ul>
 *
 * <p>Runs in the caller's transaction. The result is only as current as that transaction's
 * snapshot; the commit holds the account's import lock and the batch's row lock while it applies
 * it.
 */
@Service
public class ImportRecheckService {

  private final ImportRowRawRepository rowRepository;
  private final ImportRowRawBatchRepository rowBatchRepository;
  private final ImportDuplicateService duplicateService;
  private final ImportRowJsonService rowJson;

  public ImportRecheckService(
      ImportRowRawRepository rowRepository,
      ImportRowRawBatchRepository rowBatchRepository,
      ImportDuplicateService duplicateService,
      ImportRowJsonService rowJson) {
    this.rowRepository = rowRepository;
    this.rowBatchRepository = rowBatchRepository;
    this.duplicateService = duplicateService;
    this.rowJson = rowJson;
  }

  /** The batch's rows a commit can import ({@code PARSED}, {@code DUPLICATE}), in file order. */
  public List<ImportRowRaw> readableRows(ImportBatch batch) {
    return rowRepository.findByImportBatchIdAndParseStatusInOrderByRowNumber(
        batch.getId(), ImportBatchValues.ROW_READABLE);
  }

  /**
   * What a commit would do now with those of {@code readable} ({@link #readableRows}) that it would
   * not take as stored; {@link ImportRecheck#NONE} unless the batch is {@code PARSED}.
   */
  public ImportRecheck recheck(ImportBatch batch, List<ImportRowRaw> readable) {
    return recheckWithInclusion(batch, readable, null);
  }

  /**
   * Evaluate a PATCH that includes a formerly excluded PARSED row. Ordinary preview and commit
   * reads do not allocate duplicate matches to excluded new rows. When a member explicitly opts
   * one in, look for a matching booking only after the other active rows have reserved theirs.
   * That lets the member force in the row as a duplicate instead of having a later recheck silently
   * undo the inclusion.
   */
  public ImportRecheck recheckForInclusion(
      ImportBatch batch, List<ImportRowRaw> readable, UUID rowId) {
    return recheckWithInclusion(batch, readable, rowId);
  }

  private ImportRecheck recheckWithInclusion(
      ImportBatch batch, List<ImportRowRaw> readable, UUID rowToInclude) {
    if (!ImportBatchValues.PARSED.equals(batch.getStatus()) || readable.isEmpty()) {
      return ImportRecheck.NONE;
    }
    // At most one hold per row: a row has at most one successor, so its chain is a line. Should
    // that ever break, toMap fails rather than keep an arbitrary one.
    Map<UUID, UUID> held =
        rowRepository.findDuplicateHolds(batch.getId()).stream()
            .collect(
                Collectors.toMap(
                    ImportDuplicateHold::getRowId, ImportDuplicateHold::getTransactionId));
    Set<UUID> reserved = new HashSet<>(held.values());
    List<ParsedImportRow> unheld = new ArrayList<>();
    ImportRowRaw optedIn = null;
    for (ImportRowRaw row : readable) {
      if (held.containsKey(row.getId())) {
        continue;
      }
      if (ImportBatchValues.ROW_DUPLICATE.equals(row.getParseStatus()) || row.isIncluded()) {
        unheld.add(
            ParsedImportRow.parsed(
                row.getRowNumber(), rowJson.stringMap(row.getRawData()), rowJson.canonical(row)));
      } else if (row.getId().equals(rowToInclude)
          && ImportBatchValues.ROW_PARSED.equals(row.getParseStatus())) {
        optedIn = row;
      }
    }
    Map<Integer, UUID> nowDuplicates =
        new LinkedHashMap<>(
            duplicateService.findDuplicates(
                batch.getAccountId(), batch.getSourceKind(), unheld, reserved));
    if (optedIn != null) {
      // An excluded row must not take a matching ledger booking away from one already included.
      Set<UUID> alreadyMatched = new HashSet<>(reserved);
      alreadyMatched.addAll(nowDuplicates.values());
      Map<Integer, UUID> matches =
          duplicateService.findDuplicates(
              batch.getAccountId(),
              batch.getSourceKind(),
              List.of(
                  ParsedImportRow.parsed(
                      optedIn.getRowNumber(),
                      rowJson.stringMap(optedIn.getRawData()),
                      rowJson.canonical(optedIn))),
              alreadyMatched);
      UUID match = matches.get(optedIn.getRowNumber());
      if (match != null) {
        nowDuplicates.put(optedIn.getRowNumber(), match);
      }
    }

    Map<UUID, ImportRowRecheck> changed = new LinkedHashMap<>();
    for (ImportRowRaw row : readable) {
      UUID match = nowDuplicates.get(row.getRowNumber());
      if (ImportBatchValues.ROW_DUPLICATE.equals(row.getParseStatus())) {
        UUID holder = held.get(row.getId());
        UUID now = holder != null ? holder : match;
        if (now == null) {
          changed.put(row.getId(), ImportRowRecheck.becameNew(row.isIncluded()));
        } else if (!now.equals(row.getDuplicateOfTransactionId())) {
          changed.put(row.getId(), ImportRowRecheck.repointed(now, row.isIncluded()));
        }
      } else if ((row.isIncluded() || row.getId().equals(rowToInclude)) && match != null) {
        changed.put(
            row.getId(), ImportRowRecheck.becameDuplicate(match, row.isIncluded()));
      }
    }
    return new ImportRecheck(changed);
  }

  /**
   * Stores {@code recheck} on the rows and the batch's duplicate count; the caller saves the batch.
   * The rows are written in bulk, so managed entities of them are stale afterwards.
   */
  public void apply(ImportBatch batch, ImportRecheck recheck) {
    if (recheck.isEmpty()) {
      return;
    }
    List<UUID> becameNew = new ArrayList<>();
    List<UUID> repointed = new ArrayList<>();
    List<UUID> repointedTo = new ArrayList<>();
    List<UUID> becameDuplicates = new ArrayList<>();
    List<UUID> duplicateOf = new ArrayList<>();
    recheck
        .rows()
        .forEach(
            (rowId, row) -> {
              if (ImportBatchValues.RECHECK_NEW.equals(row.recheck())) {
                becameNew.add(rowId);
              } else if (ImportBatchValues.RECHECK_REPOINTED.equals(row.recheck())) {
                repointed.add(rowId);
                repointedTo.add(row.duplicateOfTransactionId());
              } else {
                becameDuplicates.add(rowId);
                duplicateOf.add(row.duplicateOfTransactionId());
              }
            });
    if (!becameNew.isEmpty()) {
      rowBatchRepository.markNew(becameNew);
    }
    if (!repointed.isEmpty()) {
      rowBatchRepository.repointDuplicates(repointed, repointedTo);
    }
    if (!becameDuplicates.isEmpty()) {
      rowBatchRepository.markDuplicates(becameDuplicates, duplicateOf);
    }
    batch.setDuplicateRowCount(batch.getDuplicateRowCount() + recheck.duplicateDelta());
  }
}
