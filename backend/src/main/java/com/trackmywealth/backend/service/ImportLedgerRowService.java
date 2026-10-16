package com.trackmywealth.backend.service;

import com.trackmywealth.backend.dto.CanonicalImportRow;
import com.trackmywealth.backend.dto.CreateTransactionRequest;
import com.trackmywealth.backend.dto.ImportLedgerRow;
import java.math.BigDecimal;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * How an import row becomes a ledger row (US-07-04): the request a member would send for the same
 * booking, so the import goes through the manual entry's own rules ({@link TransactionService}),
 * and the source data kept with it. The preview, the duplicate check and the commit all build rows
 * here, so what is previewed is what is committed. Stateless.
 *
 * <p>{@code merchant_description} is the row's description, or its counterparty when it has none;
 * the counterparty, value date, bank transaction code and every raw cell go to {@code
 * raw_source_data} ({@code CategorizationService} reads {@code bankTransactionCode} from there, and
 * the MCC is added by the ledger as for a manual row).
 */
@Service
public class ImportLedgerRowService {

  static final String CELLS_KEY = "cells";
  static final String COUNTERPARTY_KEY = "counterpartyName";
  static final String VALUE_DATE_KEY = "valueDate";
  static final String BANK_TRANSACTION_CODE_KEY = "bankTransactionCode";

  // Unicode-aware: a bank's no-break space separates words like any other blank.
  private static final Pattern WHITESPACE = Pattern.compile("[\\s\\u00A0]+");

  private final ObjectMapper objectMapper;

  public ImportLedgerRowService(ObjectMapper objectMapper) {
    this.objectMapper = objectMapper;
  }

  /** The ledger row for {@code row}, its raw cells {@code rawData} kept with it. */
  public ImportLedgerRow toLedgerRow(CanonicalImportRow row, Map<String, String> rawData) {
    return new ImportLedgerRow(request(row), rawSourceData(row, rawData));
  }

  /**
   * {@code row} without its bank reference: a duplicate the member forces in although the ledger
   * holds its reference already (US-07-04) is a second booking, which the reference cannot name
   * twice. The reference stays in the row's raw cells.
   */
  public static CanonicalImportRow withoutReference(CanonicalImportRow row) {
    return new CanonicalImportRow(
        row.bookingDate(),
        row.valueDate(),
        row.amount(),
        row.currency(),
        row.transactionType(),
        row.description(),
        row.counterpartyName(),
        null,
        row.mcc(),
        row.iso20022BankTransactionCode(),
        row.notes(),
        row.originalAmount(),
        row.originalCurrency(),
        row.bankExchangeRate(),
        row.valueDateAdjusted());
  }

  /**
   * The manual entry this row amounts to; nothing a file cannot state is set. A card purchase in
   * another currency (US-07-18) is recorded in its original amount and currency with the billed
   * amount as {@code billedAmount}: the ledger derives the rate from the two, so the booking keeps
   * exactly the amount the statement charged. The bank's printed rate is not used: it is rounded,
   * and banks quote it either way round; it stays in the row's source data.
   */
  public CreateTransactionRequest request(CanonicalImportRow row) {
    boolean originalPurchase = isOriginalCurrencyPurchase(row);
    return new CreateTransactionRequest(
        row.transactionType(),
        row.bookingDate(),
        ledgerAmount(row),
        ledgerCurrency(row),
        merchantDescription(row),
        row.mcc(),
        row.notes(),
        row.externalId(),
        null,
        originalPurchase ? row.amount() : null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null);
  }

  /** The ledger amount and currency, shared with duplicate matching for foreign card purchases. */
  public static boolean isOriginalCurrencyPurchase(CanonicalImportRow row) {
    return "CREDIT_CARD_PURCHASE".equals(row.transactionType())
        && row.amount().signum() != 0
        && row.originalAmount() != null
        && row.originalCurrency() != null
        && !row.originalCurrency().equals(row.currency());
  }

  public static BigDecimal ledgerAmount(CanonicalImportRow row) {
    return isOriginalCurrencyPurchase(row)
        ? row.originalAmount().abs().multiply(BigDecimal.valueOf(row.amount().signum()))
        : row.amount();
  }

  public static String ledgerCurrency(CanonicalImportRow row) {
    return isOriginalCurrencyPurchase(row) ? row.originalCurrency() : row.currency();
  }

  /**
   * The text the row's ledger entry is described by, and the duplicate rule pairs matches by
   * (US-07-17: it no longer decides whether a row is a duplicate).
   */
  public static String merchantDescription(CanonicalImportRow row) {
    return row.description() != null ? row.description() : row.counterpartyName();
  }

  /**
   * The form in which the duplicate rule pairs descriptions: trimmed, lower case, blanks collapsed
   * to one space; no description at all compares like an empty one.
   */
  public static String normalizedDescription(String description) {
    if (description == null) {
      return "";
    }
    return WHITESPACE.matcher(description.strip()).replaceAll(" ").toLowerCase(Locale.ROOT);
  }

  private String rawSourceData(CanonicalImportRow row, Map<String, String> rawData) {
    ObjectNode data = objectMapper.createObjectNode();
    if (row.counterpartyName() != null) {
      data.put(COUNTERPARTY_KEY, row.counterpartyName());
    }
    if (row.valueDate() != null) {
      data.put(VALUE_DATE_KEY, row.valueDate().toString());
    }
    if (row.iso20022BankTransactionCode() != null) {
      data.put(BANK_TRANSACTION_CODE_KEY, row.iso20022BankTransactionCode());
    }
    // The issuer's printed quote may be rounded or reciprocal. Preserve that exact
    // value for audit; the ledger's effective conversion derives from the billed amount.
    if (row.bankExchangeRate() != null) {
      data.put("issuerQuotedFxRate", row.bankExchangeRate().toPlainString());
    }
    if (row.originalAmount() != null) {
      data.put("issuerOriginalAmount", row.originalAmount().toPlainString());
    }
    if (row.originalCurrency() != null) {
      data.put("issuerOriginalCurrency", row.originalCurrency());
    }
    if (isOriginalCurrencyPurchase(row)) {
      data.put("issuerBilledAmount", row.amount().toPlainString());
      data.put("issuerBilledCurrency", row.currency());
    }
    ObjectNode cells = data.putObject(CELLS_KEY);
    rawData.forEach(cells::put);
    return objectMapper.writeValueAsString(data);
  }
}
