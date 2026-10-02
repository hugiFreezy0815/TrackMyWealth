package com.trackmywealth.backend.client;

import java.time.Duration;
import java.time.LocalDate;
import java.util.List;

/**
 * An external source of daily FX rates (US-06-04, #223). Implementations only read from the
 * provider; storing is {@code FxRateImportService}'s job.
 */
public interface FxRateProvider {

  /** The {@code fx_rate.source} every rate from this provider is stored under. */
  String source();

  /**
   * Every rate the provider published for {@code from} to {@code to}, both inclusive. Empty when
   * nothing was published in that range (a weekend, a holiday, a date before the provider's series
   * begins).
   *
   * @throws FxRateProviderException when the provider cannot be reached or its answer cannot be
   *     read
   */
  List<ProvidedFxRate> fetch(LocalDate from, LocalDate to);

  /**
   * {@link #fetch} for a call a user's request is waiting for: the answer must arrive within {@code
   * timeout}, what is left of the request's on-demand budget.
   *
   * @throws FxRateProviderException as {@link #fetch}, also when {@code timeout} runs out
   */
  default List<ProvidedFxRate> fetchOnDemand(LocalDate from, LocalDate to, Duration timeout) {
    return fetch(from, to);
  }
}
