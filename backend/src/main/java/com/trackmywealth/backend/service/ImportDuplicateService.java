package com.trackmywealth.backend.service;

import com.trackmywealth.backend.dto.CanonicalImportRow;
import com.trackmywealth.backend.dto.ImportDuplicateCandidate;
import com.trackmywealth.backend.dto.ParsedImportRow;
import com.trackmywealth.backend.repository.TransactionRepository;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Which import rows the ledger already holds (US-07-04), for the preview and again under the
 * commit's lock. Stateless; reads the ledger in two queries per batch, whatever its size.
 *
 * <ol>
 *   <li><b>Reference:</b> a row with an external id duplicates the account's row of the batch's
 *       source with that id.
 *   <li><b>Exact fallback</b>, for every row rule 1 did not match: the same booking date, amount,
 *       currency and normalized description ({@link ImportLedgerRowService#normalizedDescription})
 *       as a live ledger row of any source - a manual entry, or the same booking imported from
 *       another format or a bank sync. A row with an external id is not matched to a row of its own
 *       source that has another id: one reference is one booking. No date tolerance.
 *   <li><b>Multiplicity:</b> each ledger row is the duplicate of one import row at most, so of
 *       <i>n</i> identical rows in the file with <i>m</i> matches in the ledger, min(n, m) are
 *       duplicates (two equal coffees on one day are two bookings).
 * </ol>
 */
@Service
public class ImportDuplicateService {

  private static final char KEY_SEPARATOR = '\u001f';

  private final TransactionRepository transactionRepository;

  public ImportDuplicateService(TransactionRepository transactionRepository) {
    this.transactionRepository = transactionRepository;
  }

  /**
   * The ledger row each of {@code rows} duplicates, by row number; a row that is new is absent.
   *
   * @param source the {@code transaction.source} the batch writes ({@code CSV}, {@code DOCUMENT},
   *     {@code API})
   * @param rows rows with canonical values, each external id at most once
   */
  @Transactional(readOnly = true)
  public Map<Integer, UUID> findDuplicates(
      UUID accountId, String source, List<ParsedImportRow> rows) {
    Map<Integer, UUID> duplicates = new HashMap<>();
    if (rows.isEmpty()) {
      return duplicates;
    }
    Set<UUID> taken = new HashSet<>();
    matchByReference(accountId, source, rows, duplicates, taken);

    List<ParsedImportRow> open =
        rows.stream()
            .filter(row -> !duplicates.containsKey(row.rowNumber()))
            .sorted(Comparator.comparingInt(ParsedImportRow::rowNumber))
            .toList();
    if (open.isEmpty()) {
      return duplicates;
    }
    LocalDate from =
        open.stream()
            .map(row -> row.canonical().bookingDate())
            .min(LocalDate::compareTo)
            .orElseThrow();
    LocalDate to =
        open.stream()
            .map(row -> row.canonical().bookingDate())
            .max(LocalDate::compareTo)
            .orElseThrow();
    Map<String, List<ImportDuplicateCandidate>> ledgerByKey = new LinkedHashMap<>();
    for (ImportDuplicateCandidate candidate :
        transactionRepository.findLiveForDuplicateCheck(accountId, from, to)) {
      if (!taken.contains(candidate.getId())) {
        ledgerByKey.computeIfAbsent(key(candidate), k -> new ArrayList<>()).add(candidate);
      }
    }
    for (ParsedImportRow row : open) {
      List<ImportDuplicateCandidate> matches =
          ledgerByKey.getOrDefault(key(row.canonical()), Collections.emptyList());
      Iterator<ImportDuplicateCandidate> it = matches.iterator();
      while (it.hasNext()) {
        ImportDuplicateCandidate candidate = it.next();
        if (fallbackApplies(row.canonical(), source, candidate)) {
          duplicates.put(row.rowNumber(), candidate.getId());
          it.remove();
          break;
        }
      }
    }
    return duplicates;
  }

  /**
   * Which of {@code externalIds} the account's rows of {@code source} already carry: a row with one
   * of them cannot be inserted under it again ({@code uq_transaction_external_id}).
   */
  @Transactional(readOnly = true)
  public Set<String> takenReferences(UUID accountId, String source, List<String> externalIds) {
    if (externalIds.isEmpty()) {
      return Set.of();
    }
    Set<String> taken = new HashSet<>();
    for (ImportDuplicateCandidate candidate :
        transactionRepository.findByExternalIds(accountId, source, externalIds)) {
      taken.add(candidate.getExternalId());
    }
    return taken;
  }

  private void matchByReference(
      UUID accountId,
      String source,
      List<ParsedImportRow> rows,
      Map<Integer, UUID> duplicates,
      Set<UUID> taken) {
    List<String> externalIds =
        rows.stream().map(row -> row.canonical().externalId()).filter(Objects::nonNull).toList();
    if (externalIds.isEmpty()) {
      return;
    }
    Map<String, UUID> byReference = new HashMap<>();
    for (ImportDuplicateCandidate candidate :
        transactionRepository.findByExternalIds(accountId, source, externalIds)) {
      byReference.put(candidate.getExternalId(), candidate.getId());
    }
    for (ParsedImportRow row : rows) {
      UUID match = byReference.get(row.canonical().externalId());
      if (match != null) {
        duplicates.put(row.rowNumber(), match);
        taken.add(match);
      }
    }
  }

  // A row with a reference is the same booking as a row of another source, or one of its own
  // source that has no reference; a row of its own source with another reference is another one.
  private static boolean fallbackApplies(
      CanonicalImportRow row, String source, ImportDuplicateCandidate candidate) {
    return row.externalId() == null
        || !source.equals(candidate.getSource())
        || candidate.getExternalId() == null;
  }

  private static String key(CanonicalImportRow row) {
    return key(
        row.bookingDate(),
        row.amount().stripTrailingZeros().toPlainString(),
        row.currency(),
        ImportLedgerRowService.merchantDescription(row));
  }

  private static String key(ImportDuplicateCandidate candidate) {
    return key(
        candidate.getBookingDate(),
        candidate.getAmount().stripTrailingZeros().toPlainString(),
        candidate.getCurrency(),
        candidate.getMerchantDescription());
  }

  private static String key(LocalDate date, String amount, String currency, String description) {
    return String.join(
        String.valueOf(KEY_SEPARATOR),
        date.toString(),
        amount,
        currency,
        ImportLedgerRowService.normalizedDescription(description));
  }
}
