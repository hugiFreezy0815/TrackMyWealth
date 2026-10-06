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
}
