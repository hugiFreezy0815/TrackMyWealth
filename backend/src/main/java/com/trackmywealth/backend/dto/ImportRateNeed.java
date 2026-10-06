package com.trackmywealth.backend.dto;

import java.time.LocalDate;

/**
 * An exchange rate an import row needs to be recorded (US-07-04): {@code from} the row's currency
 * {@code into} the currency the ledger converts it into, on its booking {@code date}. One per
 * distinct pair and date of a batch, so each is looked up once.
 */
public record ImportRateNeed(String from, String into, LocalDate date) {

  /** The key a batch's rate lookups are kept under. */
  public String key() {
    return String.join("/", from, into, date.toString());
  }
}
