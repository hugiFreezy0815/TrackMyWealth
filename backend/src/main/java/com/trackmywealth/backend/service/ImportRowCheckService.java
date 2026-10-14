package com.trackmywealth.backend.service;

import com.trackmywealth.backend.dto.CanonicalImportRow;
import com.trackmywealth.backend.dto.CreateTransactionRequest;
import com.trackmywealth.backend.dto.ImportBatchValues;
import com.trackmywealth.backend.dto.ImportRateNeed;
import com.trackmywealth.backend.dto.ImportRowErrorValues;
import com.trackmywealth.backend.dto.ParsedImportRow;
import com.trackmywealth.backend.entity.Account;
import com.trackmywealth.backend.entity.AccountCreditCard;
import com.trackmywealth.backend.repository.AccountCreditCardRepository;
import com.trackmywealth.backend.repository.TransactionRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
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

  private static final int PAYMENT_LOOKUP_BATCH_SIZE = 400;

  static final String ARG_REASON = "reason";
  static final String ARG_CURRENCY = "currency";
  static final String ARG_ACCOUNT_CURRENCY = "accountCurrency";
  static final String ARG_DATE = "date";

  private final TransactionService transactionService;
  private final FxRateService fxRateService;
  private final ImportLedgerRowService ledgerRows;
  private final String fxDefaultSource;
  private final AccountCreditCardRepository creditCards;
  private final TransactionRepository transactions;

  @Autowired
  public ImportRowCheckService(
      TransactionService transactionService,
      FxRateService fxRateService,
      ImportLedgerRowService ledgerRows,
      @Value("${app.fx.default-source}") String fxDefaultSource,
      AccountCreditCardRepository creditCards,
      TransactionRepository transactions) {
    this.transactionService = transactionService;
    this.fxRateService = fxRateService;
    this.ledgerRows = ledgerRows;
    this.fxDefaultSource = fxDefaultSource;
    this.creditCards = creditCards;
    this.transactions = transactions;
  }

  /** Simple constructor for isolated row-check unit tests without a persistence layer. */
  ImportRowCheckService(
      TransactionService transactionService,
      FxRateService fxRateService,
      ImportLedgerRowService ledgerRows,
      String fxDefaultSource) {
    this(transactionService, fxRateService, ledgerRows, fxDefaultSource, null, null);
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
    CreateTransactionRequest request = ledgerRows.request(canonical);
    Optional<String> convertsInto;
    try {
      convertsInto = transactionService.requireRecordable(account, request);
    } catch (ResponseStatusException e) {
      Map<String, String> args = new LinkedHashMap<>();
      args.put(ARG_REASON, e.getReason());
      return error(row, ImportRowErrorValues.LEDGER_REJECTED, args);
    }
    // A billed amount carries its own rate (US-07-18): no provider rate is needed.
    if (convertsInto.isEmpty()
        || request.billedAmount() != null
        || hasRate(
            new ImportRateNeed(request.currency(), convertsInto.get(), canonical.bookingDate()),
            rates)) {
      return row;
    }
    Map<String, String> args = new LinkedHashMap<>();
    args.put(ARG_CURRENCY, request.currency());
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
      CreateTransactionRequest request = ledgerRows.request(canonical);
      Optional<String> convertsInto;
      try {
        convertsInto = transactionService.requireRecordable(account, request);
      } catch (ResponseStatusException e) {
        continue;
      }
      if (request.billedAmount() == null) {
        convertsInto.ifPresent(
            into ->
                needed.add(new ImportRateNeed(request.currency(), into, canonical.bookingDate())));
      }
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

  /**
   * The account card payments into {@code account} come from, when it is a credit card with one
   * set; read once per batch for {@link #warnings}.
   */
  public Optional<UUID> settlementSourceOf(Account account) {
    if (!account.isHasStatementCycle() || creditCards == null) {
      return Optional.empty();
    }
    return creditCards
        .findById(account.getId())
        .map(AccountCreditCard::getSettlementSourceAccountId);
  }

  /**
   * Which positive card-settlement preview rows have an available source-account payment candidate.
   * Only call with a source account the actor may currently read. Amounts are chunked to cap SQL
   * bind parameters, and payment dates are checked in memory (no N+1 query).
   */
  public Set<Integer> matchedSettlementRows(
      UUID sourceId, String currency, List<ParsedImportRow> rows) {
    if (sourceId == null || transactions == null) {
      return Set.of();
    }
    List<ParsedImportRow> credits =
        rows.stream()
            .filter(ParsedImportRow::isParsed)
            .filter(
                row ->
                    "SETTLEMENT".equals(row.canonical().transactionType())
                        && row.canonical().amount().signum() > 0)
            .toList();
    if (credits.isEmpty()) {
      return Set.of();
    }
    LocalDate from =
        credits.stream()
            .map(row -> row.canonical().bookingDate())
            .min(LocalDate::compareTo)
            .orElseThrow()
            .minusDays(SettlementDetectionService.WINDOW_DAYS);
    LocalDate to =
        credits.stream()
            .map(row -> row.canonical().bookingDate())
            .max(LocalDate::compareTo)
            .orElseThrow()
            .plusDays(SettlementDetectionService.WINDOW_DAYS);
    List<BigDecimal> amounts =
        credits.stream()
            .map(row -> row.canonical().amount().negate().stripTrailingZeros())
            .distinct()
            .toList();
    Map<BigDecimal, List<LocalDate>> datesByAmount = new HashMap<>();
    for (int i = 0; i < amounts.size(); i += PAYMENT_LOOKUP_BATCH_SIZE) {
      for (Object[] payment :
          transactions.findPairablePaymentDatesByAmounts(
              sourceId,
              currency,
              SettlementDetectionService.PAYMENT_TYPES,
              amounts.subList(i, Math.min(i + PAYMENT_LOOKUP_BATCH_SIZE, amounts.size())),
              from,
              to)) {
        BigDecimal amount = ((BigDecimal) payment[0]).stripTrailingZeros();
        datesByAmount
            .computeIfAbsent(amount, ignored -> new ArrayList<>())
            .add((LocalDate) payment[1]);
      }
    }
    Set<Integer> matched = new HashSet<>();
    for (ParsedImportRow row : credits) {
      LocalDate date = row.canonical().bookingDate();
      List<LocalDate> dates =
          datesByAmount.get(row.canonical().amount().negate().stripTrailingZeros());
      if (dates != null) {
        for (int i = 0; i < dates.size(); i++) {
          LocalDate candidate = dates.get(i);
          if (!candidate.isBefore(date.minusDays(SettlementDetectionService.WINDOW_DAYS))
              && !candidate.isAfter(date.plusDays(SettlementDetectionService.WINDOW_DAYS))) {
            dates.remove(i);
            matched.add(row.rowNumber());
            break;
          }
        }
      }
    }
    return matched;
  }

  /** The warnings of a row the ledger accepts; it stays included. */
  public List<String> warnings(
      Account account, CanonicalImportRow row, LocalDate today, boolean hasMatchingPayment) {
    List<String> warnings = new ArrayList<>();
    if (row.bookingDate().isAfter(today)) {
      warnings.add(ImportBatchValues.WARNING_FUTURE_DATE);
    }
    if (!account.getNativeCurrency().equals(row.currency())) {
      warnings.add(ImportBatchValues.WARNING_CURRENCY_DIFFERS);
    }
    // A positive card booking is a payment by default, not a refund. Highlight an
    // unmatched payment so the member can correct its template instead of silently misclassifying
    // a refund. This is advisory; settlement matching still decides after commit.
    if (account.isHasStatementCycle()
        && "SETTLEMENT".equals(row.transactionType())
        && row.amount().signum() > 0
        && !hasMatchingPayment) {
      warnings.add(ImportBatchValues.WARNING_CARD_SETTLEMENT_UNMATCHED);
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
