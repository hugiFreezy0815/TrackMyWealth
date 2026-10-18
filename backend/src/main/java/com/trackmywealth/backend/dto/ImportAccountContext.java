package com.trackmywealth.backend.dto;

import java.math.BigDecimal;

/**
 * What parsing a file for one account depends on (US-07-18): the account's currency (for a template
 * without a currency column) and what kind of account takes the rows without a mapped type - on a
 * credit card ({@code has_statement_cycle}) they are card purchases and settlements, on a loan or
 * mortgage ({@code has_amortisation}, US-07-21) interest charged and instalments, rather than
 * expenses and income.
 */
public record ImportAccountContext(String currency, boolean creditCard, boolean loan) {

  /**
   * The transaction type of a row that names none, by its signed {@code amount} and this kind of
   * account: shared by templates (a row no mapping types) and CAMT statements (#350).
   */
  public String fallbackType(BigDecimal amount) {
    boolean outflow = amount.signum() < 0;
    if (creditCard) {
      return outflow ? "CREDIT_CARD_PURCHASE" : "SETTLEMENT";
    }
    if (loan) {
      return outflow ? "EXPENSE" : "DEBT_REPAYMENT";
    }
    return outflow
        ? ImportTemplateValues.FALLBACK_TYPE_NEGATIVE
        : ImportTemplateValues.FALLBACK_TYPE_NOT_NEGATIVE;
  }
}
