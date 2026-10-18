package com.trackmywealth.backend.service;

import com.trackmywealth.backend.camt.CamtAmount;
import com.trackmywealth.backend.camt.CamtBalance;
import com.trackmywealth.backend.camt.CamtBankCode;
import com.trackmywealth.backend.camt.CamtDate;
import com.trackmywealth.backend.camt.CamtEntry;
import com.trackmywealth.backend.camt.CamtFileReader;
import com.trackmywealth.backend.camt.CamtFormatException;
import com.trackmywealth.backend.camt.CamtLimitException;
import com.trackmywealth.backend.camt.CamtMessage;
import com.trackmywealth.backend.camt.CamtStatement;
import com.trackmywealth.backend.camt.CamtTransaction;
import com.trackmywealth.backend.camt.CamtUnsupportedException;
import com.trackmywealth.backend.dto.CanonicalImportRow;
import com.trackmywealth.backend.dto.ImportAccountContext;
import com.trackmywealth.backend.dto.ImportBatchValues;
import com.trackmywealth.backend.dto.ImportFileAccountIdentifier;
import com.trackmywealth.backend.dto.ImportParseResult;
import com.trackmywealth.backend.dto.ImportRowErrorValues;
import com.trackmywealth.backend.dto.ImportStatementBalances;
import com.trackmywealth.backend.dto.ImportTemplateValues;
import com.trackmywealth.backend.dto.ParsedImportRow;
import com.trackmywealth.backend.error.ApiErrorCode;
import com.trackmywealth.backend.error.ImportFileRejectedException;
import com.trackmywealth.backend.error.ImportRowRejectedException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.TemporalAccessor;
import java.util.ArrayList;
import java.util.Currency;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;

/**
 * #350: imports an ISO 20022 CAMT statement without a template - a {@code camt.053} end-of-day
 * statement or a {@code camt.052} intraday report, one XML document or a ZIP archive of them, of
 * any bank - into the same rows, account identifier and statement balances a template's parse gives
 * ({@link ImportParseResult}). {@link CamtFileReader} reads the untrusted file within its limits;
 * this class interprets what it read. Preview, duplicate check, explicit commit, rollback and the
 * balance snapshot are the existing import's; nothing here is posted.
 *
 * <p>Each booked entry ({@code BOOK}) is one row; one that is pending or information only is shown
 * but never imported ({@code IMPORT_ROW_CAMT_NOT_BOOKED}). A collective entry is split into one row
 * per transaction detail only when the details' signed amounts, in the entry's currency, add up to
 * the entry's: otherwise it is an error row ({@code IMPORT_ROW_CAMT_DETAILS_*}), so no amount is
 * imported twice or lost. An entry with more details than {@link CamtFileReader} keeps, or fewer
 * than it declares, is imported whole at its booked amount. A value its schema does not allow is an
 * error row of its entry, never a guess.
 *
 * <p>The external id is the bank's reference ({@code AcctSvcrRef}) of the entry, or of its only or
 * split detail, scoped by kind and by the booking it names: {@code CAMT:NTRY:} or {@code CAMT:TX:},
 * booking date, signed amount and currency, then the reference. A bank that reuses a reference
 * therefore never makes one booking the duplicate of another, and an entry's reference never names
 * its details. {@code NOTPROVIDED}, {@code NONREF} and the originator's {@code EndToEndId} are no
 * booking id; without an id, the duplicate check compares booking date and amount.
 *
 * <p>The account is named by the statements' IBAN; a file of several accounts is refused. Only a
 * {@code camt.053} states a statement balance - its closing booked balance ({@code CLBD}) - never a
 * {@code camt.052}, whose balances are intraday. A date-time becomes the calendar date it is in the
 * deployment's zone ({@link BusinessDateService#zone}).
 */
@Service
public class CamtImportReaderService {

  static final String BOOKED = "BOOK";
  static final String CLOSING_BOOKED = "CLBD";
  static final String ENTRY_REFERENCE = ImportBatchValues.CAMT_REFERENCE_PREFIX + "NTRY:";
  static final String TRANSACTION_REFERENCE = ImportBatchValues.CAMT_REFERENCE_PREFIX + "TX:";
  static final String REFUND = "REFUND";

  // The row's raw cells, as the preview and the error export show them.
  static final String CELL_MESSAGE = "Message";
  static final String CELL_STATUS = "Status";
  static final String CELL_BOOKING_DATE = "BookingDate";
  static final String CELL_VALUE_DATE = "ValueDate";
  static final String CELL_AMOUNT = "Amount";
  static final String CELL_CURRENCY = "Currency";
  static final String CELL_CREDIT_DEBIT = "CreditDebit";
  static final String CELL_REVERSAL = "Reversal";
  static final String CELL_ENTRY_REFERENCE = "EntryReference";
  static final String CELL_BANK_CODE = "BankTransactionCode";
  static final String CELL_ENTRY_INFORMATION = "AdditionalEntryInformation";
  static final String CELL_TRANSACTIONS = "Transactions";
  static final String CELL_TRANSACTION = "Transaction";
  static final String CELL_TRANSACTION_AMOUNT = "TransactionAmount";
  static final String CELL_TRANSACTION_REFERENCE = "TransactionReference";
  static final String CELL_END_TO_END_ID = "EndToEndId";
  static final String CELL_CREDITOR = "Creditor";
  static final String CELL_DEBTOR = "Debtor";
  static final String CELL_REMITTANCE = "Remittance";
  static final String CELL_INSTRUCTED_AMOUNT = "InstructedAmount";
  static final String CELL_EXCHANGE_RATE = "ExchangeRate";

  // The CAMT field an error row names, in the place of a template's column.
  static final String FIELD_STATUS = "Ntry/Sts";
  static final String FIELD_AMOUNT = "Ntry/Amt";
  static final String FIELD_CURRENCY = "Ntry/Amt/@Ccy";
  static final String FIELD_CREDIT_DEBIT = "Ntry/CdtDbtInd";
  static final String FIELD_REVERSAL = "Ntry/RvslInd";
  static final String FIELD_BOOKING_DATE = "Ntry/BookgDt";
  static final String FIELD_VALUE_DATE = "Ntry/ValDt";
  static final String FIELD_DECLARED_TRANSACTIONS = "Ntry/NtryDtls/Btch/NbOfTxs";
  static final String FIELD_DETAIL_AMOUNT = "TxDtls/Amt";
  static final String FIELD_DETAIL_CURRENCY = "TxDtls/Amt/@Ccy";
  static final String FIELD_DETAIL_CREDIT_DEBIT = "TxDtls/CdtDbtInd";
  static final String FIELD_INSTRUCTED_AMOUNT = "TxDtls/AmtDtls/InstdAmt/Amt";
  static final String FIELD_INSTRUCTED_CURRENCY = "TxDtls/AmtDtls/InstdAmt/Amt/@Ccy";
  static final String FIELD_EXCHANGE_RATE = "TxDtls/AmtDtls/CcyXchg/XchgRate";
  static final String FIELD_BALANCE = "Bal";

  static final String ARG_STATUS = "status";
  static final String ARG_ENTRY_AMOUNT = "entryAmount";
  static final String ARG_DETAIL_TOTAL = "detailTotal";
  static final String ARG_ENTRY_CURRENCY = "entryCurrency";
  static final String ARG_DETAIL_CURRENCY = "detailCurrency";

  private static final String ARG_COLUMN = "column";
  private static final String ARG_VALUE = "value";
  private static final String ARG_PATTERN = "pattern";
  private static final String DATE_PATTERN = "yyyy-MM-dd";
  private static final String DATE_TIME_PATTERN = "yyyy-MM-dd'T'HH:mm:ss";
  private static final String CREDIT = "CRDT";
  private static final String DEBIT = "DBIT";
  private static final String SEPARATOR = ":";
  private static final Set<String> NO_REFERENCE = Set.of("NOTPROVIDED", "NONREF");
  private static final Set<String> TRUE = Set.of("true", "1");
  private static final Set<String> FALSE = Set.of("false", "0");
  // ISO amounts and rates: unsigned decimals, at most 18 digits; the ledger keeps NUMERIC(20,4).
  private static final Pattern DECIMAL = Pattern.compile("\\d{1,18}(\\.\\d{1,17})?");
  private static final Pattern COUNT = Pattern.compile("\\d{1,15}");
  private static final Pattern CURRENCY_CODE = Pattern.compile("[A-Z]{3}");
  // An xs:date may carry an offset; the calendar date is the one written.
  private static final Pattern ISO_DATE =
      Pattern.compile("(\\d{4}-\\d{2}-\\d{2})(Z|[+-]\\d{2}:\\d{2})?");
  // A file names one account at most (a named limit, as PMD requires).
  private static final int ONE_ACCOUNT = 1;
  private static final int MAX_INTEGER_DIGITS = 16;
  private static final int MAX_FRACTION_DIGITS = 4;
  private static final int MAX_RATE_FRACTION_DIGITS = 10;
  // ISO Max35Text: a longer reference is no valid AcctSvcrRef, so it is no booking id either.
  private static final int MAX_REFERENCE_LENGTH = 35;
  private static final int MAX_STATUS_LENGTH = 35;
  // ISO Max140Text for names, Max500Text for the additional information.
  private static final int MAX_NAME_LENGTH = 140;
  private static final int MAX_DESCRIPTION_LENGTH = 500;
  private static final int MAX_CODE_LENGTH = 100;
  private static final int MAX_CELL_LENGTH = 500;

  private final ZoneId zone;

  public CamtImportReaderService(BusinessDateService businessDates) {
    this.zone = businessDates.zone();
  }

  /**
   * Whether {@code content} is XML or a ZIP archive, which no template reads: such a file uploaded
   * without a template is read as a CAMT statement. Told by its bytes, never by its name.
   */
  public boolean isCamtFile(byte[] content) {
    return CamtFileReader.isCandidate(content);
  }

  /**
   * The rows, account identifier and statement balances of the CAMT file {@code content}, for an
   * import into an account of the kind {@code account} describes.
   *
   * @throws ImportFileRejectedException 422: {@code IMPORT_CAMT_UNSUPPORTED} (no supported CAMT
   *     message), {@code IMPORT_FILE_MALFORMED} (not readable), {@code IMPORT_CAMT_LIMIT_EXCEEDED},
   *     {@code IMPORT_CAMT_MULTIPLE_ACCOUNTS}, {@code IMPORT_FILE_NO_DATA_ROWS} (no entry) or
   *     {@code IMPORT_FILE_TOO_MANY_ROWS}
   */
  public ImportParseResult parse(byte[] content, ImportAccountContext account) {
    List<CamtMessage> messages = read(content);
    String iban = requireOneAccount(messages);
    List<ParsedImportRow> rows = new ArrayList<>();
    for (CamtMessage message : messages) {
      for (CamtStatement statement : message.statements()) {
        for (CamtEntry entry : statement.entries()) {
          addEntry(rows, message, entry, account);
        }
      }
    }
    if (rows.isEmpty()) {
      throw new ImportFileRejectedException(
          ApiErrorCode.IMPORT_FILE_NO_DATA_ROWS, "The CAMT file holds no entry.");
    }
    return new ImportParseResult(List.of(), null, rows, identifier(iban), balances(messages));
  }

  private static List<CamtMessage> read(byte[] content) {
    try {
      return CamtFileReader.read(content);
    } catch (CamtLimitException e) {
      throw new ImportFileRejectedException(
          ApiErrorCode.IMPORT_CAMT_LIMIT_EXCEEDED, e.getMessage(), e);
    } catch (CamtUnsupportedException e) {
      throw new ImportFileRejectedException(
          ApiErrorCode.IMPORT_CAMT_UNSUPPORTED, e.getMessage(), e);
    } catch (CamtFormatException e) {
      // Its message is the reader's own; the parser exception it wraps is never shown.
      throw new ImportFileRejectedException(ApiErrorCode.IMPORT_FILE_MALFORMED, e.getMessage(), e);
    }
  }

  // The IBAN all statements name, or null. A file never chooses between two accounts: an import
  // goes into the one account the member picked, so statements of several are refused.
  private static String requireOneAccount(List<CamtMessage> messages) {
    List<String> accounts =
        messages.stream()
            .flatMap(message -> message.statements().stream())
            .map(CamtImportReaderService::accountKey)
            .distinct()
            .toList();
    if (accounts.size() > ONE_ACCOUNT) {
      throw new ImportFileRejectedException(
          ApiErrorCode.IMPORT_CAMT_MULTIPLE_ACCOUNTS,
          "The file holds statements of "
              + accounts.size()
              + " accounts; import each account's statements into that account.");
    }
    return messages.stream()
        .flatMap(message -> message.statements().stream())
        .map(CamtStatement::iban)
        .filter(Objects::nonNull)
        .findFirst()
        .orElse(null);
  }

  private static String accountKey(CamtStatement statement) {
    if (statement.iban() != null) {
      return "IBAN" + SEPARATOR + compact(statement.iban());
    }
    return statement.otherAccountId() == null
        ? ""
        : "OTHR" + SEPARATOR + statement.otherAccountId();
  }

  private static String compact(String identifier) {
    return identifier.replaceAll("\\s", "").toUpperCase(Locale.ROOT);
  }

  // Found when the IBAN is valid; one without valid check digits, or an account without an IBAN,
  // is named but not found, so the member confirms the account (US-07-10).
  private static ImportFileAccountIdentifier identifier(String iban) {
    return new ImportFileAccountIdentifier(
        ImportTemplateValues.IDENTIFIER_IBAN,
        iban == null
            ? null
            : AccountIdentifierService.normalize(iban, ImportTemplateValues.IDENTIFIER_IBAN)
                .orElse(null));
  }

  // --- entries -----------------------------------------------------------------------------------

  private void addEntry(
      List<ParsedImportRow> rows,
      CamtMessage message,
      CamtEntry entry,
      ImportAccountContext account) {
    Map<String, String> cells = entryCells(message, entry);
    int number = rows.size() + 1;
    List<ParsedImportRow> entryRows;
    try {
      entryRows = entryRows(number, cells, entry, account);
    } catch (ImportRowRejectedException e) {
      entryRows = List.of(ParsedImportRow.error(number, cells, e.getCode(), e.getArgs()));
    }
    if (rows.size() + entryRows.size() > ImportFileParserService.MAX_DATA_ROWS) {
      throw ImportFileParserService.tooManyRows();
    }
    rows.addAll(entryRows);
  }

  private List<ParsedImportRow> entryRows(
      int number, Map<String, String> cells, CamtEntry entry, ImportAccountContext account) {
    if (entry.status() == null) {
      throw rowError(ImportRowErrorValues.VALUE_MISSING, ARG_COLUMN, FIELD_STATUS);
    }
    if (!BOOKED.equals(entry.status())) {
      return List.of(
          ParsedImportRow.skipped(
              number,
              cells,
              ImportRowErrorValues.CAMT_NOT_BOOKED,
              ImportFileParserService.rowArgs(
                  ARG_STATUS, bounded(entry.status(), MAX_STATUS_LENGTH))));
    }
    BigDecimal amount =
        signed(unsigned(entry.amount(), FIELD_AMOUNT), entry.creditDebit(), FIELD_CREDIT_DEBIT);
    String currency = currency(entry.amount(), FIELD_CURRENCY);
    boolean reversal = reversal(entry.reversal());
    LocalDate booked = requiredDate(entry.bookingDate(), FIELD_BOOKING_DATE);
    LocalDate valued =
        entry.valueDate() == null ? null : requiredDate(entry.valueDate(), FIELD_VALUE_DATE);
    CanonicalImportRow base =
        new CanonicalImportRow(
            booked,
            valued,
            amount,
            currency,
            type(amount, reversal, account),
            bounded(entry.additionalInfo(), MAX_DESCRIPTION_LENGTH),
            null,
            null,
            null,
            bankCode(entry.bankCode()),
            null);
    List<CamtTransaction> details = entry.transactions();
    boolean complete = isComplete(entry);
    if (complete && details.size() > 1) {
      return split(number, cells, entry, base, reversal, account);
    }
    CamtTransaction only = complete && details.size() == 1 ? details.get(0) : null;
    CanonicalImportRow row = only == null ? base : withDetail(base, only);
    String reference = reference(ENTRY_REFERENCE, entry.accountServicerReference(), row);
    if (reference == null && only != null) {
      reference = reference(TRANSACTION_REFERENCE, only.accountServicerReference(), row);
    }
    Map<String, String> rowCells = only == null ? cells : detailCells(cells, only, 1, 1);
    return List.of(row(number, rowCells, withReference(row, reference)));
  }

  // Every detail of the entry was read, and as many as a collective entry declares.
  private static boolean isComplete(CamtEntry entry) {
    if (entry.transactionsOmitted()) {
      return false;
    }
    String declared = entry.declaredTransactions();
    if (declared == null) {
      return true;
    }
    if (!COUNT.matcher(declared).matches()) {
      throw rowError(
          ImportRowErrorValues.CAMT_VALUE_INVALID,
          ARG_COLUMN,
          FIELD_DECLARED_TRANSACTIONS,
          ARG_VALUE,
          declared);
    }
    return Long.parseLong(declared) == entry.transactions().size();
  }

  // One row per detail, only when the details' signed amounts add up to the entry's.
  private List<ParsedImportRow> split(
      int first,
      Map<String, String> cells,
      CamtEntry entry,
      CanonicalImportRow base,
      boolean reversal,
      ImportAccountContext account) {
    List<CamtTransaction> details = entry.transactions();
    List<CanonicalImportRow> parts = new ArrayList<>(details.size());
    BigDecimal total = BigDecimal.ZERO;
    for (CamtTransaction detail : details) {
      String detailCurrency = currency(detail.amount(), FIELD_DETAIL_CURRENCY);
      if (!detailCurrency.equals(base.currency())) {
        throw rowError(
            ImportRowErrorValues.CAMT_DETAILS_CURRENCY,
            ARG_ENTRY_CURRENCY,
            base.currency(),
            ARG_DETAIL_CURRENCY,
            detailCurrency);
      }
      // Before .001.04 a detail has no direction of its own: it is the entry's.
      String direction = detail.creditDebit() == null ? entry.creditDebit() : detail.creditDebit();
      BigDecimal part =
          signed(
              unsigned(detail.amount(), FIELD_DETAIL_AMOUNT), direction, FIELD_DETAIL_CREDIT_DEBIT);
      total = total.add(part);
      parts.add(withDetail(withAmount(base, part, type(part, reversal, account)), detail));
    }
    if (total.compareTo(base.amount()) != 0) {
      throw rowError(
          ImportRowErrorValues.CAMT_DETAILS_MISMATCH,
          ARG_ENTRY_AMOUNT,
          base.amount().toPlainString(),
          ARG_DETAIL_TOTAL,
          total.toPlainString());
    }
    List<ParsedImportRow> rows = new ArrayList<>(parts.size());
    for (int i = 0; i < parts.size(); i++) {
      CamtTransaction detail = details.get(i);
      CanonicalImportRow part = parts.get(i);
      rows.add(
          row(
              first + i,
              detailCells(cells, detail, i + 1, parts.size()),
              withReference(
                  part,
                  reference(TRANSACTION_REFERENCE, detail.accountServicerReference(), part))));
    }
    return rows;
  }

  private static ParsedImportRow row(
      int number, Map<String, String> cells, CanonicalImportRow canonical) {
    return canonical.amount().signum() == 0
        ? ParsedImportRow.skipped(number, cells, canonical)
        : ParsedImportRow.parsed(number, cells, canonical);
  }

  // A reversal that pays back is a refund (on a cash account or a card); anything else is typed by
  // its sign and the account, as a template's row without a type is.
  private static String type(BigDecimal amount, boolean reversal, ImportAccountContext account) {
    return reversal && amount.signum() > 0 && !account.loan()
        ? REFUND
        : account.fallbackType(amount);
  }

  // The detail's text, counterparty, bank code and original amount, onto the entry's row.
  private static CanonicalImportRow withDetail(CanonicalImportRow row, CamtTransaction detail) {
    String description = bounded(String.join(" ", detail.remittance()), MAX_DESCRIPTION_LENGTH);
    if (description == null) {
      description = bounded(detail.additionalInfo(), MAX_DESCRIPTION_LENGTH);
    }
    if (description == null) {
      description = row.description();
    }
    // The other party: the creditor is paid by a debit, the debtor pays a credit.
    String counterparty =
        bounded(
            row.amount().signum() < 0 ? detail.creditorName() : detail.debtorName(),
            MAX_NAME_LENGTH);
    String bankCode = bankCode(detail.bankCode());
    BigDecimal originalAmount = null;
    String originalCurrency = null;
    BigDecimal rate = null;
    CamtAmount instructed = detail.instructedAmount();
    if (instructed != null && instructed.value() != null) {
      String instructedCurrency = currency(instructed, FIELD_INSTRUCTED_CURRENCY);
      if (!instructedCurrency.equals(row.currency())) {
        originalAmount = unsigned(instructed, FIELD_INSTRUCTED_AMOUNT);
        originalCurrency = instructedCurrency;
        if (detail.exchangeRate() != null) {
          rate = rate(detail.exchangeRate());
        }
      }
    }
    return new CanonicalImportRow(
        row.bookingDate(),
        row.valueDate(),
        row.amount(),
        row.currency(),
        row.transactionType(),
        description,
        counterparty,
        row.externalId(),
        row.mcc(),
        bankCode == null ? row.iso20022BankTransactionCode() : bankCode,
        row.notes(),
        originalAmount,
        originalCurrency,
        rate);
  }

  private static CanonicalImportRow withAmount(
      CanonicalImportRow row, BigDecimal amount, String type) {
    return new CanonicalImportRow(
        row.bookingDate(),
        row.valueDate(),
        amount,
        row.currency(),
        type,
        row.description(),
        row.counterpartyName(),
        row.externalId(),
        row.mcc(),
        row.iso20022BankTransactionCode(),
        row.notes());
  }

  private static CanonicalImportRow withReference(CanonicalImportRow row, String externalId) {
    if (externalId == null) {
      return row;
    }
    return new CanonicalImportRow(
        row.bookingDate(),
        row.valueDate(),
        row.amount(),
        row.currency(),
        row.transactionType(),
        row.description(),
        row.counterpartyName(),
        externalId,
        row.mcc(),
        row.iso20022BankTransactionCode(),
        row.notes(),
        row.originalAmount(),
        row.originalCurrency(),
        row.bankExchangeRate());
  }

  // The scoped id of the booking row names with the bank's reference, or null without one.
  private static String reference(String kind, String reference, CanonicalImportRow row) {
    if (reference == null
        || reference.length() > MAX_REFERENCE_LENGTH
        || NO_REFERENCE.contains(reference.toUpperCase(Locale.ROOT))) {
      return null;
    }
    return kind
        + row.bookingDate()
        + SEPARATOR
        + row.amount().stripTrailingZeros().toPlainString()
        + SEPARATOR
        + row.currency()
        + SEPARATOR
        + reference;
  }

  private static String bankCode(CamtBankCode code) {
    if (code == null) {
      return null;
    }
    if (code.domain() != null) {
      List<String> parts = new ArrayList<>(List.of(code.domain()));
      if (code.family() != null) {
        parts.add(code.family());
      }
      if (code.subFamily() != null) {
        parts.add(code.subFamily());
      }
      return bounded(String.join("-", parts), MAX_CODE_LENGTH);
    }
    if (code.proprietary() == null) {
      return null;
    }
    return bounded(
        code.issuer() == null
            ? code.proprietary()
            : code.proprietary() + " (" + code.issuer() + ")",
        MAX_CODE_LENGTH);
  }

  // --- the raw cells -----------------------------------------------------------------------------

  private static Map<String, String> entryCells(CamtMessage message, CamtEntry entry) {
    Map<String, String> cells = new LinkedHashMap<>();
    cells.put(CELL_MESSAGE, message.messageType() + "." + message.version());
    put(cells, CELL_STATUS, entry.status());
    put(cells, CELL_BOOKING_DATE, text(entry.bookingDate()));
    put(cells, CELL_VALUE_DATE, text(entry.valueDate()));
    if (entry.amount() != null) {
      put(cells, CELL_AMOUNT, entry.amount().value());
      put(cells, CELL_CURRENCY, entry.amount().currency());
    }
    put(cells, CELL_CREDIT_DEBIT, entry.creditDebit());
    put(cells, CELL_REVERSAL, entry.reversal());
    put(cells, CELL_ENTRY_REFERENCE, entry.accountServicerReference());
    put(cells, CELL_BANK_CODE, bankCode(entry.bankCode()));
    put(cells, CELL_ENTRY_INFORMATION, entry.additionalInfo());
    if (!entry.transactions().isEmpty()) {
      put(cells, CELL_TRANSACTIONS, Integer.toString(entry.transactions().size()));
    }
    return cells;
  }

  private static Map<String, String> detailCells(
      Map<String, String> entryCells, CamtTransaction detail, int position, int count) {
    Map<String, String> cells = new LinkedHashMap<>(entryCells);
    cells.put(CELL_TRANSACTION, position + "/" + count);
    if (detail.amount() != null) {
      put(cells, CELL_TRANSACTION_AMOUNT, detail.amount().value());
    }
    put(cells, CELL_TRANSACTION_REFERENCE, detail.accountServicerReference());
    put(cells, CELL_END_TO_END_ID, detail.endToEndId());
    put(cells, CELL_CREDITOR, detail.creditorName());
    put(cells, CELL_DEBTOR, detail.debtorName());
    put(cells, CELL_REMITTANCE, String.join(" ", detail.remittance()));
    CamtAmount instructed = detail.instructedAmount();
    if (instructed != null && instructed.value() != null) {
      put(
          cells,
          CELL_INSTRUCTED_AMOUNT,
          instructed.currency() == null
              ? instructed.value()
              : instructed.value() + " " + instructed.currency());
    }
    put(cells, CELL_EXCHANGE_RATE, detail.exchangeRate());
    return cells;
  }

  private static void put(Map<String, String> cells, String name, String value) {
    String bounded = bounded(value, MAX_CELL_LENGTH);
    if (bounded != null) {
      cells.put(name, bounded);
    }
  }

  private static String text(CamtDate date) {
    if (date == null) {
      return null;
    }
    return date.date() != null ? date.date() : date.dateTime();
  }

  // --- balances ----------------------------------------------------------------------------------

  // A camt.053's closing booked balances; null (no balance rule) for camt.052 reports only. A CLBD
  // that cannot be read hides the statement balance rather than a wrong one being recorded.
  private ImportStatementBalances balances(List<CamtMessage> messages) {
    if (messages.stream().noneMatch(CamtMessage::isStatement)) {
      return null;
    }
    List<ImportStatementBalances.Stated> stated = new ArrayList<>();
    List<String> unreadable = new ArrayList<>();
    for (CamtMessage message : messages) {
      if (!message.isStatement()) {
        continue;
      }
      for (CamtStatement statement : message.statements()) {
        for (CamtBalance balance : statement.balances()) {
          if (!CLOSING_BOOKED.equals(balance.type())) {
            continue;
          }
          try {
            stated.add(
                new ImportStatementBalances.Stated(
                    requiredDate(balance.date(), FIELD_BALANCE),
                    signed(
                        unsigned(balance.amount(), FIELD_BALANCE),
                        balance.creditDebit(),
                        FIELD_BALANCE),
                    currency(balance.amount(), FIELD_BALANCE)));
          } catch (ImportRowRejectedException e) {
            unreadable.add(readableCurrency(balance.amount()));
          }
        }
      }
    }
    return new ImportStatementBalances(stated, unreadable, false);
  }

  private static String readableCurrency(CamtAmount amount) {
    try {
      return currency(amount, FIELD_BALANCE);
    } catch (ImportRowRejectedException e) {
      return null;
    }
  }

  // --- values ------------------------------------------------------------------------------------

  private static BigDecimal unsigned(CamtAmount amount, String field) {
    if (amount == null || amount.value() == null) {
      throw rowError(ImportRowErrorValues.VALUE_MISSING, ARG_COLUMN, field);
    }
    String text = amount.value();
    if (!DECIMAL.matcher(text).matches()) {
      throw rowError(ImportRowErrorValues.CAMT_VALUE_INVALID, ARG_COLUMN, field, ARG_VALUE, text);
    }
    BigDecimal number = new BigDecimal(text);
    try {
      // Trailing zeros past the ledger's four fraction digits are no precision lost.
      BigDecimal stored =
          number.scale() > MAX_FRACTION_DIGITS
              ? number.setScale(MAX_FRACTION_DIGITS, RoundingMode.UNNECESSARY)
              : number;
      if (stored.precision() - stored.scale() <= MAX_INTEGER_DIGITS) {
        return stored;
      }
    } catch (ArithmeticException e) {
      throw new ImportRowRejectedException(
          ImportRowErrorValues.AMOUNT_OUT_OF_RANGE,
          ImportFileParserService.rowArgs(ARG_COLUMN, field, ARG_VALUE, text),
          e);
    }
    throw rowError(ImportRowErrorValues.AMOUNT_OUT_OF_RANGE, ARG_COLUMN, field, ARG_VALUE, text);
  }

  // The XML amount is unsigned: a debit leaves the account, a credit enters it.
  private static BigDecimal signed(BigDecimal amount, String direction, String field) {
    if (direction == null) {
      throw rowError(ImportRowErrorValues.VALUE_MISSING, ARG_COLUMN, field);
    }
    if (DEBIT.equals(direction)) {
      return amount.negate();
    }
    if (CREDIT.equals(direction)) {
      return amount;
    }
    throw rowError(
        ImportRowErrorValues.CAMT_VALUE_INVALID, ARG_COLUMN, field, ARG_VALUE, direction);
  }

  private static String currency(CamtAmount amount, String field) {
    if (amount == null || amount.currency() == null) {
      throw rowError(ImportRowErrorValues.VALUE_MISSING, ARG_COLUMN, field);
    }
    String code = amount.currency();
    if (!CURRENCY_CODE.matcher(code).matches()) {
      throw rowError(ImportRowErrorValues.CURRENCY_INVALID, ARG_COLUMN, field, ARG_VALUE, code);
    }
    try {
      return Currency.getInstance(code).getCurrencyCode();
    } catch (IllegalArgumentException e) {
      throw new ImportRowRejectedException(
          ImportRowErrorValues.CURRENCY_INVALID,
          ImportFileParserService.rowArgs(ARG_COLUMN, field, ARG_VALUE, code),
          e);
    }
  }

  private static BigDecimal rate(String text) {
    if (!DECIMAL.matcher(text).matches()) {
      throw rowError(
          ImportRowErrorValues.CAMT_VALUE_INVALID,
          ARG_COLUMN,
          FIELD_EXCHANGE_RATE,
          ARG_VALUE,
          text);
    }
    BigDecimal rate = new BigDecimal(text);
    return rate.scale() > MAX_RATE_FRACTION_DIGITS
        ? rate.setScale(MAX_RATE_FRACTION_DIGITS, RoundingMode.HALF_UP)
        : rate;
  }

  private static boolean reversal(String text) {
    if (text == null || FALSE.contains(text)) {
      return false;
    }
    if (TRUE.contains(text)) {
      return true;
    }
    throw rowError(
        ImportRowErrorValues.CAMT_VALUE_INVALID, ARG_COLUMN, FIELD_REVERSAL, ARG_VALUE, text);
  }

  private LocalDate requiredDate(CamtDate date, String field) {
    if (date == null || date.isEmpty()) {
      throw rowError(ImportRowErrorValues.VALUE_MISSING, ARG_COLUMN, field);
    }
    if (date.date() != null) {
      Matcher matcher = ISO_DATE.matcher(date.date());
      try {
        if (matcher.matches()) {
          return LocalDate.parse(matcher.group(1));
        }
      } catch (DateTimeParseException e) {
        throw unparseable(field, date.date(), DATE_PATTERN, e);
      }
      throw unparseable(field, date.date(), DATE_PATTERN, null);
    }
    try {
      TemporalAccessor parsed =
          DateTimeFormatter.ISO_DATE_TIME.parseBest(
              date.dateTime(), OffsetDateTime::from, LocalDateTime::from);
      // A moment is the date it is here; a local date-time is the date it names.
      return parsed instanceof OffsetDateTime moment
          ? moment.atZoneSameInstant(zone).toLocalDate()
          : LocalDateTime.from(parsed).toLocalDate();
    } catch (DateTimeParseException e) {
      throw unparseable(field, date.dateTime(), DATE_TIME_PATTERN, e);
    }
  }

  private static ImportRowRejectedException unparseable(
      String field, String value, String pattern, Throwable cause) {
    return new ImportRowRejectedException(
        ImportRowErrorValues.DATE_UNPARSEABLE,
        ImportFileParserService.rowArgs(ARG_COLUMN, field, ARG_VALUE, value, ARG_PATTERN, pattern),
        cause);
  }

  private static String bounded(String value, int max) {
    if (value == null) {
      return null;
    }
    String stripped = value.strip();
    if (stripped.isEmpty()) {
      return null;
    }
    return stripped.length() > max ? stripped.substring(0, max) : stripped;
  }

  private static ImportRowRejectedException rowError(String code, String... namesAndValues) {
    return new ImportRowRejectedException(code, ImportFileParserService.rowArgs(namesAndValues));
  }
}
