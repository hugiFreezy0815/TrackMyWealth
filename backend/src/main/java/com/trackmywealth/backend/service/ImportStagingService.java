package com.trackmywealth.backend.service;

import com.trackmywealth.backend.dto.CanonicalImportRow;
import com.trackmywealth.backend.dto.ImportBatchValues;
import com.trackmywealth.backend.dto.ImportRowErrorValues;
import com.trackmywealth.backend.dto.ParsedImportRow;
import com.trackmywealth.backend.dto.StagedImportRow;
import com.trackmywealth.backend.entity.Account;
import com.trackmywealth.backend.entity.ImportBatch;
import com.trackmywealth.backend.repository.ImportBatchRepository;
import com.trackmywealth.backend.repository.ImportRowRawBatchRepository;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * Turns parsed rows into an import batch's preview (US-07-04): each row is stored {@code PARSED}
 * (new, included), {@code DUPLICATE} (excluded, with the ledger row it duplicates) or {@code ERROR}
 * (excluded), with its warnings, and the batch becomes {@code PARSED}. A batch parsed again loses
 * its earlier rows first.
 *
 * <p>Source-agnostic on purpose (#230 amendment, EPIC 24): it takes {@link ParsedImportRow}s and
 * the batch, never a file or a template, so a bank sync feeds the same preview, and it needs no
 * HTTP request, so a background job can call it. In order: a repeated bank reference is an error
 * from its second row on ({@code IMPORT_ROW_EXTERNAL_ID_REPEATED}); a row the ledger would refuse
 * is an error ({@link ImportRowCheckService}); then the duplicate rules ({@link
 * ImportDuplicateService}) run over the rest.
 */
@Service
public class ImportStagingService {

  private static final Logger LOG = LoggerFactory.getLogger(ImportStagingService.class);

  static final String ARG_EXTERNAL_ID = "externalId";
  static final String ARG_FIRST_ROW = "firstRow";

  private final ImportBatchRepository batchRepository;
  private final ImportRowRawBatchRepository rowBatchRepository;
  private final ImportRowCheckService rowCheckService;
  private final ImportDuplicateService duplicateService;
  private final ObjectMapper objectMapper;
  private final Clock clock;

  public ImportStagingService(
      ImportBatchRepository batchRepository,
      ImportRowRawBatchRepository rowBatchRepository,
      ImportRowCheckService rowCheckService,
      ImportDuplicateService duplicateService,
      ObjectMapper objectMapper,
      Clock clock) {
    this.batchRepository = batchRepository;
    this.rowBatchRepository = rowBatchRepository;
    this.rowCheckService = rowCheckService;
    this.duplicateService = duplicateService;
    this.objectMapper = objectMapper;
    this.clock = clock;
  }

  /**
   * Stores {@code rows} as the preview of {@code batch}, an import into {@code account}, replacing
   * any earlier rows, and marks the batch {@code PARSED} with its counts. The caller holds the
   * batch's lock and has checked that it may be parsed.
   */
  @Transactional
  public void stage(ImportBatch batch, Account account, List<ParsedImportRow> rows) {
    List<ParsedImportRow> checked = rejectRepeatedReferences(rows);
    Map<String, Boolean> rates = new HashMap<>();
    List<ParsedImportRow> recordable = new ArrayList<>();
    for (int i = 0; i < checked.size(); i++) {
      ParsedImportRow row = checked.get(i);
      if (row.isParsed()) {
        row = rowCheckService.checkRecordable(account, row, rates);
        checked.set(i, row);
      }
      if (row.isParsed()) {
        recordable.add(row);
      }
    }
    Map<Integer, UUID> duplicates =
        duplicateService.findDuplicates(account.getId(), batch.getSourceKind(), recordable);

    LocalDate today = LocalDate.now(clock);
    List<StagedImportRow> staged = new ArrayList<>(checked.size());
    int duplicateCount = 0;
    int errorCount = 0;
    for (ParsedImportRow row : checked) {
      UUID duplicateOf = duplicates.get(row.rowNumber());
      if (duplicateOf != null) {
        duplicateCount++;
      } else if (!row.isParsed()) {
        errorCount++;
      }
      staged.add(staged(row, duplicateOf, account, today));
    }
    rowBatchRepository.deleteByBatch(batch.getId());
    rowBatchRepository.insertAll(batch.getId(), batch.getWorkspaceId(), staged);

    batch.setStatus(ImportBatchValues.PARSED);
    batch.setParsedAt(OffsetDateTime.now(clock));
    batch.setRowCount(checked.size());
    batch.setDuplicateRowCount(duplicateCount);
    batch.setErrorRowCount(errorCount);
    batchRepository.saveAndFlush(batch);
    if (LOG.isInfoEnabled()) {
      LOG.info(
          "Staged import batch {}: {} rows, {} duplicates, {} errors",
          batch.getId(),
          checked.size(),
          duplicateCount,
          errorCount);
    }
  }

  // One bank reference is one booking: from the second row carrying it on, a row is an error.
  private static List<ParsedImportRow> rejectRepeatedReferences(List<ParsedImportRow> rows) {
    Map<String, Integer> firstRowByReference = new HashMap<>();
    List<ParsedImportRow> checked = new ArrayList<>(rows.size());
    for (ParsedImportRow row : rows) {
      String reference = row.isParsed() ? row.canonical().externalId() : null;
      Integer first = reference == null ? null : firstRowByReference.get(reference);
      if (reference != null && first == null) {
        firstRowByReference.put(reference, row.rowNumber());
      }
      if (first == null) {
        checked.add(row);
      } else {
        Map<String, String> args = new LinkedHashMap<>();
        args.put(ARG_EXTERNAL_ID, reference);
        args.put(ARG_FIRST_ROW, Integer.toString(first));
        checked.add(
            new ParsedImportRow(
                row.rowNumber(),
                row.rawData(),
                ImportRowErrorValues.STATUS_ERROR,
                ImportRowErrorValues.EXTERNAL_ID_REPEATED,
                args,
                row.canonical()));
      }
    }
    return checked;
  }

  private StagedImportRow staged(
      ParsedImportRow row, UUID duplicateOf, Account account, LocalDate today) {
    CanonicalImportRow canonical = row.canonical();
    String status;
    if (!row.isParsed()) {
      status = ImportBatchValues.ROW_ERROR;
    } else if (duplicateOf != null) {
      status = ImportBatchValues.ROW_DUPLICATE;
    } else {
      status = ImportBatchValues.ROW_PARSED;
    }
    List<String> warnings =
        row.isParsed() ? rowCheckService.warnings(account, canonical, today) : List.of();
    return new StagedImportRow(
        UUID.randomUUID(),
        row.rowNumber(),
        objectMapper.writeValueAsString(row.rawData()),
        status,
        ImportBatchValues.ROW_PARSED.equals(status),
        duplicateOf,
        warnings,
        row.errorCode(),
        row.isParsed() ? null : objectMapper.writeValueAsString(row.errorArgs()),
        canonical == null ? null : objectMapper.writeValueAsString(canonical),
        canonical == null ? null : canonical.bookingDate(),
        canonical == null ? null : canonical.amount(),
        canonical == null ? null : canonical.currency());
  }
}
