package com.trackmywealth.backend.service;

import com.trackmywealth.backend.dto.CanonicalImportRow;
import com.trackmywealth.backend.dto.CreateTransactionRequest;
import com.trackmywealth.backend.dto.ImportLedgerRow;
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
        row.notes());
  }

  /** The manual entry this row amounts to; nothing a file cannot state is set. */
  public CreateTransactionRequest request(CanonicalImportRow row) {
    return new CreateTransactionRequest(
        row.transactionType(),
        row.bookingDate(),
        row.amount(),
        row.currency(),
        merchantDescription(row),
        row.mcc(),
        row.notes(),
        row.externalId(),
        null,
        null,
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

  /** The text the row's ledger entry is described by, and the duplicate rule compares. */
  public static String merchantDescription(CanonicalImportRow row) {
    return row.description() != null ? row.description() : row.counterpartyName();
  }

  /**
   * The duplicate rule's form of a description: trimmed, lower case, blanks collapsed to one space;
   * no description at all compares like an empty one.
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
    ObjectNode cells = data.putObject(CELLS_KEY);
    rawData.forEach(cells::put);
    return objectMapper.writeValueAsString(data);
  }
}
