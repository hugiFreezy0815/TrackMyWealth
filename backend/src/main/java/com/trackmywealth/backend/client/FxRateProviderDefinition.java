package com.trackmywealth.backend.client;

import java.util.Currency;

/**
 * Static metadata for one selectable FX provider (#226).
 *
 * <p>The definition is deliberately a separate bean from {@link FxRateProvider}: provider
 * implementations are conditional and only the selected one is instantiated, while definitions stay
 * available so startup can list valid provider names and historical stored sources keep the hub
 * currency they were derived through after the active provider changes.
 *
 * <p>Keep a definition registered for as long as {@code fx_rate} holds rows of its source, even
 * after its client is retired: {@code FxRateService} resolves a source's hub only from its
 * definition, so without it that source's derived cross rates and every chain through its hub stop
 * resolving, and conversions reading it fail. Only its stored direct pairs would still be served.
 */
public record FxRateProviderDefinition(String name, String source, String hubCurrency) {

  public FxRateProviderDefinition {
    if (name == null || name.isBlank()) {
      throw new IllegalArgumentException("FX provider name is required");
    }
    if (source == null || source.isBlank()) {
      throw new IllegalArgumentException("FX provider source is required");
    }
    if (hubCurrency == null || hubCurrency.isBlank()) {
      throw new IllegalArgumentException("FX provider hub currency is required");
    }
    try {
      Currency.getInstance(hubCurrency);
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException(
          "FX provider hub currency '" + hubCurrency + "' is not an ISO 4217 currency", e);
    }
  }
}
