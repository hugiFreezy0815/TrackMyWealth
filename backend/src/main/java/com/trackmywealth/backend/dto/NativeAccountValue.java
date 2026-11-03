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
 * @param sourceDate the date of the dated observation the value rests on (a snapshot, a manual
 *     valuation or a loan statement), {@code null} for a value derived from the ledger alone or the
 *     loan terms
 * @param confirmedOn the latest date the value was confirmed by anything recorded - the observation
 *     itself, or for a loan statement also a later ledger row (#445) - which its staleness is
 *     judged by; {@code null} for a value that never goes stale
 */
public record NativeAccountValue(
    BigDecimal amount, String basis, LocalDate sourceDate, LocalDate confirmedOn) {

  public NativeAccountValue(BigDecimal amount, String basis) {
    this(amount, basis, null, null);
  }

  /** A value resting on an observation that only that observation confirms. */
  public NativeAccountValue(BigDecimal amount, String basis, LocalDate sourceDate) {
    this(amount, basis, sourceDate, sourceDate);
  }
}
