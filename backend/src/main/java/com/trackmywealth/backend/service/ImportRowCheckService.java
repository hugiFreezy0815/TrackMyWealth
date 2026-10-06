package com.trackmywealth.backend.service;

import com.trackmywealth.backend.dto.CanonicalImportRow;
import com.trackmywealth.backend.dto.ImportBatchValues;
import com.trackmywealth.backend.dto.ImportRateNeed;
import com.trackmywealth.backend.dto.ImportRowErrorValues;
import com.trackmywealth.backend.dto.ParsedImportRow;
import com.trackmywealth.backend.entity.Account;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * Checks a parsed import row against the ledger before the member sees it (US-07-04): whether the
 * ledger would record it on the account as it records a manual entry ({@link
 * TransactionService#requireRecordable}), including an exchange rate for a row in another currency,
 * and which warnings it carries. A row the ledger would refuse becomes an {@code ERROR} row here,
 * visible and exportable, so the commit itself does not fail on it (product owner decision
 * 2026-10-06). Its canonical values stay, so a reconciliation can still find it.
 */
@Service
public class ImportRowCheckService {

  static final String ARG_REASON = "reason";
  static final String ARG_CURRENCY = "currency";
  static final String ARG_ACCOUNT_CURRENCY = "accountCurrency";
  static final String ARG_DATE = "date";

  private final TransactionService transactionService;
  private final FxRateService fxRateService;
  private final ImportLedgerRowService ledgerRows;
  private final String fxDefaultSource;

  public ImportRowCheckService(
      TransactionService transactionService,
      FxRateService fxRateService,
      ImportLedgerRowService ledgerRows,
      @Value("${app.fx.default-source}") String fxDefaultSource) {
    this.transactionService = transactionService;
    this.fxRateService = fxRateService;
    this.ledgerRows = ledgerRows;
    this.fxDefaultSource = fxDefaultSource;
  }

  /**
   * {@code row} unchanged when the ledger would record it, otherwise the same row as an {@code
   * ERROR}: {@code IMPORT_ROW_FX_RATE_UNAVAILABLE} when no rate converts it into the account's
   * currency on its booking date (#223: the provider is asked for a missing date, as for a manual
   * entry), {@code IMPORT_ROW_LEDGER_REJECTED} with the ledger's reason for anything else.
   *
   * @param rates whether a rate exists, by {@link ImportRateNeed#key()}, shared by one batch's rows
   *     so each is looked up once; normally filled beforehand by {@link #lookUpRates}, outside the
   *     caller's transaction, and a pair it lacks is looked up here
   */
  public ParsedImportRow checkRecordable(
      Account account, ParsedImportRow row, Map<String, Boolean> rates) {
    CanonicalImportRow canonical = row.canonical();
    Optional<String> convertsInto;
    try {
      convertsInto = transactionService.requireRecordable(account, ledgerRows.request(canonical));
    } catch (ResponseStatusException e) {
      Map<String, String> args = new LinkedHashMap<>();
      args.put(ARG_REASON, e.getReason());
      return error(row, ImportRowErrorValues.LEDGER_REJECTED, args);
    }
    if (convertsInto.isEmpty()
        || hasRate(
            new ImportRateNeed(canonical.currency(), convertsInto.get(), canonical.bookingDate()),
            rates)) {
      return row;
    }
    Map<String, String> args = new LinkedHashMap<>();
    args.put(ARG_CURRENCY, canonical.currency());
    args.put(ARG_ACCOUNT_CURRENCY, convertsInto.get());
    args.put(ARG_DATE, canonical.bookingDate().toString());
    return error(row, ImportRowErrorValues.FX_RATE_UNAVAILABLE, args);
  }

  /**
   * The exchange rates the readable {@code rows} need to be recorded on {@code account}, one per
   * pair and date. Reads the database only (a card's billing currency), so the caller can ask it in
   * a short transaction and then {@link #lookUpRates} outside it. A row the ledger refuses needs
   * none: {@link #checkRecordable} makes it an error row anyway.
   */
  public Set<ImportRateNeed> ratesNeeded(Account account, List<ParsedImportRow> rows) {
    Set<ImportRateNeed> needed = new LinkedHashSet<>();
    for (ParsedImportRow row : rows) {
      if (!row.isParsed()) {
        continue;
      }
      CanonicalImportRow canonical = row.canonical();
      Optional<String> convertsInto;
      try {
        convertsInto = transactionService.requireRecordable(account, ledgerRows.request(canonical));
      } catch (ResponseStatusException e) {
        continue;
      }
      convertsInto.ifPresent(
          into ->
              needed.add(new ImportRateNeed(canonical.currency(), into, canonical.bookingDate())));
    }
    return needed;
  }

  /**
   * Whether a rate exists for each of {@code needed}, by {@link ImportRateNeed#key()}; for a date
   * no stored rate covers, the provider is asked first (#223), as for a manual entry. Call it
   * outside the transaction that stages the batch: the provider can take seconds, and the batch's
   * lock must not be held meanwhile (#230 review).
   */
  public Map<String, Boolean> lookUpRates(Collection<ImportRateNeed> needed) {
    Map<String, Boolean> rates = new HashMap<>();
    for (ImportRateNeed need : needed) {
      hasRate(need, rates);
    }
    return rates;
  }

  /** The warnings of a row the ledger accepts; it stays included. */
  public List<String> warnings(Account account, CanonicalImportRow row, LocalDate today) {
    List<String> warnings = new ArrayList<>();
    if (row.bookingDate().isAfter(today)) {
      warnings.add(ImportBatchValues.WARNING_FUTURE_DATE);
    }
    if (!account.getNativeCurrency().equals(row.currency())) {
      warnings.add(ImportBatchValues.WARNING_CURRENCY_DIFFERS);
    }
    return warnings;
  }

  /**
   * The message arguments of {@code warning} on {@code row}, in message order. Recomputed from the
   * row when it is shown, so only the codes are stored.
   */
  public static Map<String, String> warningArgs(
      String warning, CanonicalImportRow row, String accountCurrency) {
    Map<String, String> args = new LinkedHashMap<>();
    if (ImportBatchValues.WARNING_FUTURE_DATE.equals(warning)) {
      args.put(ARG_DATE, row.bookingDate().toString());
    } else if (ImportBatchValues.WARNING_CURRENCY_DIFFERS.equals(warning)) {
      args.put(ARG_CURRENCY, row.currency());
      args.put(ARG_ACCOUNT_CURRENCY, accountCurrency);
    }
    return args;
  }

  private boolean hasRate(ImportRateNeed need, Map<String, Boolean> rates) {
    return rates.computeIfAbsent(
        need.key(),
        key ->
            fxRateService
                .tryGetConversionRateFetchingMissing(
                    need.from(), need.into(), need.date(), fxDefaultSource)
                .isPresent());
  }

  private static ParsedImportRow error(ParsedImportRow row, String code, Map<String, String> args) {
    return new ParsedImportRow(
        row.rowNumber(),
        row.rawData(),
        ImportRowErrorValues.STATUS_ERROR,
        code,
        args,
        row.canonical());
  }
}
