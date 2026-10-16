package com.trackmywealth.backend.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * A ledger row an import row may duplicate (US-07-04): what the duplicate rules compare. A query
 * projection of {@code TransactionRepository}; internal, not part of any API response. It lives
 * here because {@code ArchitectureTest} allows only repositories in {@code ..repository..}.
 */
public interface ImportDuplicateCandidate {
  UUID getId();

  LocalDate getBookingDate();

  BigDecimal getAmount();

  String getCurrency();

  String getMerchantDescription();

  String getSource();

  String getExternalId();

  // The three below are read by the cross-currency rule (#341) only: findLiveForDuplicateCheck
  // selects them, the reference and preview queries do not, and they are null there.

  /** The rate to the account's currency; {@code null} for a row in the account's own currency. */
  BigDecimal getFxRateToAccountCurrency();

  /** Whether that rate is an estimated daily rate rather than one derived from a billed amount. */
  Boolean getFxRateEstimated();

  /** The account's own currency (#341: a foreign purchase's billed amount is in it). */
  String getAccountCurrency();
}
