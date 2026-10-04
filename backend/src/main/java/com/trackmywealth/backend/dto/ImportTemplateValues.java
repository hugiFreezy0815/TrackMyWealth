package com.trackmywealth.backend.dto;

import java.util.List;
import java.util.Set;

/**
 * Stable API values of a CSV import template (US-07-03, V15 check constraints). Sprint 5 parses
 * cash transactions into an account the member picks at upload; the other template classes and
 * account identification strategies exist in the schema but are refused with {@code
 * IMPORT_TEMPLATE_UNSUPPORTED}.
 */
public final class ImportTemplateValues {

  private ImportTemplateValues() {}

  public static final String CLASS_CASH_TRANSACTIONS = "CASH_TRANSACTIONS";

  /** One signed amount column; a negative value is money leaving the account. */
  public static final String AMOUNT_SINGLE_SIGNED_COLUMN = "SINGLE_SIGNED_COLUMN";

  /** A debit and a credit column, exactly one of them filled per row (e.g. Soll/Haben). */
  public static final String AMOUNT_SEPARATE_DEBIT_CREDIT = "SEPARATE_DEBIT_CREDIT";

  /** One amount column, negative values written as {@code (12.50)}. */
  public static final String AMOUNT_NEGATIVE_IN_PARENTHESES = "NEGATIVE_IN_PARENTHESES";

  public static final Set<String> AMOUNT_REPRESENTATIONS =
      Set.of(
          AMOUNT_SINGLE_SIGNED_COLUMN,
          AMOUNT_SEPARATE_DEBIT_CREDIT,
          AMOUNT_NEGATIVE_IN_PARENTHESES);

  public static final String CURRENCY_FIXED = "FIXED";
  public static final String CURRENCY_PER_ROW = "PER_ROW";
  public static final String CURRENCY_FROM_ACCOUNT = "FROM_ACCOUNT";

  public static final Set<String> CURRENCY_MODES =
      Set.of(CURRENCY_FIXED, CURRENCY_PER_ROW, CURRENCY_FROM_ACCOUNT);

  public static final String ACCOUNT_USER_SELECTED = "USER_SELECTED";

  public static final String ENCODING_UTF_8 = "UTF-8";
  public static final String ENCODING_ISO_8859_1 = "ISO-8859-1";
  public static final String ENCODING_WINDOWS_1252 = "windows-1252";

  /** The encodings a template may name, in their canonical spelling. */
  public static final List<String> ENCODINGS =
      List.of(ENCODING_UTF_8, ENCODING_ISO_8859_1, ENCODING_WINDOWS_1252);

  /** {@code header_row_index} of a file without a header row: columns are mapped by index. */
  public static final int NO_HEADER_ROW = -1;

  /** Every {@code transaction.transaction_type} (V10). */
  public static final Set<String> TRANSACTION_TYPES =
      Set.of(
          "INCOME",
          "EXPENSE",
          "TRANSFER",
          "DEPOSIT",
          "WITHDRAWAL",
          "BUY",
          "SELL",
          "DIVIDEND",
          "INTEREST",
          "FEE",
          "TAX",
          "REFUND",
          "DEBT_REPAYMENT",
          "PENSION_CONTRIBUTION",
          "CREDIT_CARD_PURCHASE",
          "SETTLEMENT",
          "VALUATION_ADJUSTMENT",
          "CORPORATE_ACTION");

  /** The transaction types a {@link #CLASS_CASH_TRANSACTIONS} template may produce. */
  public static final Set<String> CASH_TRANSACTION_TYPES =
      Set.of(
          "INCOME",
          "EXPENSE",
          "TRANSFER",
          "DEPOSIT",
          "WITHDRAWAL",
          "INTEREST",
          "FEE",
          "TAX",
          "REFUND",
          "DEBT_REPAYMENT",
          "PENSION_CONTRIBUTION",
          "CREDIT_CARD_PURCHASE",
          "SETTLEMENT");

  /** The fallback type of a row whose type column is empty or unmapped, by the amount's sign. */
  public static final String FALLBACK_TYPE_NEGATIVE = "EXPENSE";

  public static final String FALLBACK_TYPE_NOT_NEGATIVE = "INCOME";
}
