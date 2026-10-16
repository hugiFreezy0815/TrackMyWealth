package com.trackmywealth.backend.service;

import com.trackmywealth.backend.dto.CanonicalImportRow;
import com.trackmywealth.backend.dto.ImportDuplicateCandidate;
import com.trackmywealth.backend.dto.ImportDuplicateOfResponse;
import com.trackmywealth.backend.dto.ParsedImportRow;
import com.trackmywealth.backend.repository.TransactionRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Currency;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Which import rows the ledger already holds (US-07-04), for the preview and again under the
 * commit's lock. Stateless; reads the ledger in two queries per batch, whatever its size.
 *
 * <ol>
 *   <li><b>Reference:</b> a row with an external id duplicates the account's row of the batch's
 *       source with that id.
 *   <li><b>Fallback</b>, for every row rule 1 did not match: the same booking date, amount and
 *       currency as a live ledger row of any source - a manual entry, or the same booking imported
 *       from another format or a bank sync. The description is not compared (US-07-17): two export
 *       formats of one account word the same booking differently. A row with an external id is not
 *       matched to a row of its own source that has another id: one reference is one booking. No
 *       date tolerance.
 *   <li><b>Across currencies</b> (#341), for every row rules 1 and 2 did not match: a foreign card
 *       purchase, booked in its original currency ({@link
 *       ImportLedgerRowService#isOriginalCurrencyPurchase}), is the same booking as a ledger row of
 *       its billed amount in the account's currency, and a row in the account's currency the same
 *       booking as a ledger row in another currency whose rate was stated or derived from a billed
 *       amount (not estimated), when that row's amount at its rate, rounded half up to the
 *       currency's minor unit, is the row's amount. So a purchase a template version or a manual
 *       entry booked in euros and another one in its original currency is not booked twice. Only
 *       such cross-currency pairs: two foreign purchases are compared by rule 2 alone.
 *   <li><b>Multiplicity:</b> each ledger row is the duplicate of one import row at most, so of
 *       <i>n</i> rows in the file with <i>m</i> matches in the ledger, min(n, m) are duplicates
 *       (two equal coffees on one day are two bookings). Where several ledger rows match, a row is
 *       paired with one of the same normalized description ({@link
 *       ImportLedgerRowService#normalizedDescription}) first, so the preview shows the closest
 *       booking; the pairing never costs a duplicate: as many rows are paired as the rule allows.
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
    return findDuplicates(accountId, source, rows, Set.of());
  }

  /**
   * As {@link #findDuplicates(UUID, String, List)}, but no row may match a ledger row of {@code
   * reserved}: other rows of the batch are its duplicates already (the commit's re-check, #287).
   */
  @Transactional(readOnly = true)
  public Map<Integer, UUID> findDuplicates(
      UUID accountId, String source, List<ParsedImportRow> rows, Set<UUID> reserved) {
    Map<Integer, UUID> duplicates = new HashMap<>();
    if (rows.isEmpty()) {
      return duplicates;
    }
    Set<UUID> taken = new HashSet<>(reserved);
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
    Map<String, List<ParsedImportRow>> openByKey = new LinkedHashMap<>();
    for (ParsedImportRow row : open) {
      openByKey.computeIfAbsent(key(row.canonical()), k -> new ArrayList<>()).add(row);
    }
    openByKey.forEach(
        (key, keyRows) -> {
          List<ImportDuplicateCandidate> candidates = ledgerByKey.get(key);
          if (candidates != null) {
            matchByFallback(keyRows, candidates, source, duplicates);
          }
        });
    Set<UUID> paired = new HashSet<>(duplicates.values());
    matchAcrossCurrencies(
        open.stream().filter(row -> !duplicates.containsKey(row.rowNumber())).toList(),
        ledgerByKey.values().stream()
            .flatMap(List::stream)
            .filter(candidate -> !paired.contains(candidate.getId()))
            .toList(),
        source,
        duplicates);
    return duplicates;
  }

  // Rule 3 (#341): foreign purchases against ledger rows in the account's currency, then rows in
  // the account's currency against converted ledger rows. The two passes pair disjoint rows and
  // ledger rows, by the amount both stand for in the account's currency.
  private static void matchAcrossCurrencies(
      List<ParsedImportRow> rows,
      List<ImportDuplicateCandidate> candidates,
      String source,
      Map<Integer, UUID> duplicates) {
    if (rows.isEmpty() || candidates.isEmpty()) {
      return;
    }
    Map<Boolean, List<ParsedImportRow>> byForeign =
        rows.stream()
            .collect(
                Collectors.partitioningBy(
                    row -> ImportLedgerRowService.isOriginalCurrencyPurchase(row.canonical())));
    matchByCrossCurrencyKey(
        byForeign.get(true),
        candidates,
        ImportDuplicateService::accountCurrencyKey,
        source,
        duplicates);
    matchByCrossCurrencyKey(
        byForeign.get(false), candidates, ImportDuplicateService::convertedKey, source, duplicates);
  }

  // Pairs rows, by their billed amount and currency, with the ledger rows {@code ledgerKey} gives a
  // key in the account's currency; a ledger row it gives none is not compared.
  private static void matchByCrossCurrencyKey(
      List<ParsedImportRow> rows,
      List<ImportDuplicateCandidate> candidates,
      Function<ImportDuplicateCandidate, String> ledgerKey,
      String source,
      Map<Integer, UUID> duplicates) {
    if (rows.isEmpty()) {
      return;
    }
    Map<String, List<ImportDuplicateCandidate>> ledgerByKey = new LinkedHashMap<>();
    for (ImportDuplicateCandidate candidate : candidates) {
      String key = ledgerKey.apply(candidate);
      if (key != null) {
        ledgerByKey.computeIfAbsent(key, k -> new ArrayList<>()).add(candidate);
      }
    }
    Map<String, List<ParsedImportRow>> rowsByKey = new LinkedHashMap<>();
    for (ParsedImportRow row : rows) {
      CanonicalImportRow canonical = row.canonical();
      rowsByKey
          .computeIfAbsent(
              key(canonical.bookingDate(), canonical.amount(), canonical.currency()),
              k -> new ArrayList<>())
          .add(row);
    }
    rowsByKey.forEach(
        (key, keyRows) -> {
          List<ImportDuplicateCandidate> keyCandidates = ledgerByKey.get(key);
          if (keyCandidates != null) {
            matchByFallback(keyRows, keyCandidates, source, duplicates);
          }
        });
  }

  // A ledger row booked in the account's own currency, by its key; null for any other.
  private static String accountCurrencyKey(ImportDuplicateCandidate candidate) {
    return candidate.getCurrency().equals(candidate.getAccountCurrency()) ? key(candidate) : null;
  }

  // A ledger row in another currency, by the amount it stands for in the account's currency; null
  // for one in the account's currency, or whose rate is missing or estimated - an estimated daily
  // rate does not give back what the card issuer billed.
  private static String convertedKey(ImportDuplicateCandidate candidate) {
    BigDecimal rate = candidate.getFxRateToAccountCurrency();
    if (candidate.getCurrency().equals(candidate.getAccountCurrency())
        || rate == null
        || !Boolean.FALSE.equals(candidate.getFxRateEstimated())) {
      return null;
    }
    int minorUnits =
        Currency.getInstance(candidate.getAccountCurrency()).getDefaultFractionDigits();
    if (minorUnits < 0) {
      return null;
    }
    return key(
        candidate.getBookingDate(),
        candidate.getAmount().multiply(rate).setScale(minorUnits, RoundingMode.HALF_UP),
        candidate.getAccountCurrency());
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

  /**
   * The ledger rows {@code ids} of the account as the preview shows them next to the import rows
   * that duplicate them (US-07-17), by id; an id the account does not hold is absent. One query.
   */
  @Transactional(readOnly = true)
  public Map<UUID, ImportDuplicateOfResponse> duplicateTargets(UUID accountId, Set<UUID> ids) {
    Map<UUID, ImportDuplicateOfResponse> targets = new HashMap<>();
    if (ids.isEmpty()) {
      return targets;
    }
    for (ImportDuplicateCandidate candidate :
        transactionRepository.findDuplicateTargets(accountId, ids)) {
      targets.put(
          candidate.getId(),
          new ImportDuplicateOfResponse(
              candidate.getBookingDate(),
              candidate.getAmount(),
              candidate.getCurrency(),
              candidate.getMerchantDescription()));
    }
    return targets;
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
      if (match != null && !taken.contains(match)) {
        duplicates.put(row.rowNumber(), match);
        taken.add(match);
      }
    }
  }

  /**
   * Pairs rows and ledger rows of one booking date, amount and currency, as many as {@link
   * #fallbackApplies} allows: rows and ledger rows of the same normalized description first, the
   * rest in file order, then each row left over takes a ledger row another row could do without (an
   * augmenting path). Greedy pairing alone can spend the only ledger row a referenced row may match
   * on a row that had another one, and import the referenced row twice.
   */
  private static void matchByFallback(
      List<ParsedImportRow> rows,
      List<ImportDuplicateCandidate> candidates,
      String source,
      Map<Integer, UUID> duplicates) {
    int[] rowOfCandidate = new int[candidates.size()];
    int[] candidateOfRow = new int[rows.size()];
    Arrays.fill(rowOfCandidate, -1);
    Arrays.fill(candidateOfRow, -1);
    boolean[][] applies = new boolean[rows.size()][candidates.size()];
    boolean[][] sameDescription = new boolean[rows.size()][candidates.size()];
    List<String> candidateDescriptions =
        candidates.stream()
            .map(c -> ImportLedgerRowService.normalizedDescription(c.getMerchantDescription()))
            .toList();
    for (int r = 0; r < rows.size(); r++) {
      CanonicalImportRow row = rows.get(r).canonical();
      String description =
          ImportLedgerRowService.normalizedDescription(
              ImportLedgerRowService.merchantDescription(row));
      for (int c = 0; c < candidates.size(); c++) {
        applies[r][c] = fallbackApplies(row, source, candidates.get(c));
        sameDescription[r][c] = applies[r][c] && description.equals(candidateDescriptions.get(c));
      }
    }
    for (boolean[][] allowed : List.of(sameDescription, applies)) {
      for (int r = 0; r < rows.size(); r++) {
        for (int c = 0; c < candidates.size() && candidateOfRow[r] < 0; c++) {
          if (allowed[r][c] && rowOfCandidate[c] < 0) {
            rowOfCandidate[c] = r;
            candidateOfRow[r] = c;
          }
        }
      }
    }
    for (int r = 0; r < rows.size(); r++) {
      if (candidateOfRow[r] < 0) {
        augment(applies, rowOfCandidate, candidateOfRow, new boolean[candidates.size()], r);
      }
    }
    for (int r = 0; r < rows.size(); r++) {
      if (candidateOfRow[r] >= 0) {
        duplicates.put(rows.get(r).rowNumber(), candidates.get(candidateOfRow[r]).getId());
      }
    }
  }

  // Kuhn's augmenting path: the row takes a ledger row that is free, or whose row can move on to
  // another one. Each row already paired stays paired.
  private static boolean augment(
      boolean[][] applies, int[] rowOfCandidate, int[] candidateOfRow, boolean[] visited, int row) {
    for (int c = 0; c < rowOfCandidate.length; c++) {
      if (applies[row][c] && !visited[c]) {
        visited[c] = true;
        if (rowOfCandidate[c] < 0
            || augment(applies, rowOfCandidate, candidateOfRow, visited, rowOfCandidate[c])) {
          rowOfCandidate[c] = row;
          candidateOfRow[row] = c;
          return true;
        }
      }
    }
    return false;
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
    // A foreign card purchase is booked in its original amount and currency (US-07-18).
    return key(
        row.bookingDate(),
        ImportLedgerRowService.ledgerAmount(row),
        ImportLedgerRowService.ledgerCurrency(row));
  }

  private static String key(ImportDuplicateCandidate candidate) {
    return key(candidate.getBookingDate(), candidate.getAmount(), candidate.getCurrency());
  }

  private static String key(LocalDate date, BigDecimal amount, String currency) {
    return String.join(
        String.valueOf(KEY_SEPARATOR),
        date.toString(),
        amount.stripTrailingZeros().toPlainString(),
        currency);
  }
}
