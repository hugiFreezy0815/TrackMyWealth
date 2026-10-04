package com.trackmywealth.backend.client;

import java.time.Duration;
import java.time.LocalDate;
import java.util.List;

/**
 * An external source of daily FX rates (US-06-04, #223). Implementations only read from the
 * provider; storing is {@code FxRateImportService}'s job.
 *
 * <p>#226: exactly one implementation is loaded, the one {@code app.fx.import.provider} ({@code
 * FX_IMPORT_PROVIDER}) names. Each is conditional on its own name, reads its settings from {@code
 * app.fx.import.providers.<name>}, and registers an unconditional {@link FxRateProviderDefinition}
 * so provider discovery and historical source-to-hub resolution do not depend on the active client.
 */
public interface FxRateProvider {

  /**
   * The same {@link FxRateProviderDefinition} the provider registers as a bean (#226) - its name,
   * the {@code fx_rate.source} its rates are stored under, and its hub currency, stated once.
   */
  FxRateProviderDefinition definition();

  /**
   * Every rate the provider published for {@code from} to {@code to}, both inclusive. Empty when
   * nothing was published in that range (a weekend, a holiday, a date before the provider's series
   * begins). Every rate is quoted from the hub, {@code <hub>/<currency>} with a currency other than
   * the hub (#226): the import refuses an answer with any other pair, since no cross rate could be
   * derived from it, and like any provider failure that ends the run.
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
