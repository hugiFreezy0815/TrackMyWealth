package com.trackmywealth.backend.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * An account's value in its own currency together with where it came from ({@link
 * ValueBasisValues}) - the intermediate {@code AccountValuationService} resolves before any FX
 * conversion. Internal to valuation: not part of any API response. It lives here rather than as a
 * nested type of the service because {@code ArchitectureTest} requires every class in {@code
 * ..service..} to be a {@code @Service}.
 *
 * @param sourceDate the date of the dated observation the value rests on (a snapshot or a manual
 *     valuation), {@code null} for a value derived from the ledger or the loan terms
 */
public record NativeAccountValue(BigDecimal amount, String basis, LocalDate sourceDate) {

  public NativeAccountValue(BigDecimal amount, String basis) {
    this(amount, basis, null);
  }
}
