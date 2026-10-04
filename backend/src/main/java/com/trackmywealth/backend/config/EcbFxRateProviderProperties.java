package com.trackmywealth.backend.config;

import java.net.URI;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The ECB provider's own settings (#226), apart from the provider-neutral ones in {@link
 * FxRateImportProperties}.
 *
 * @param baseUrl the ECB data API's EXR dataflow; the daily euro reference rates are read from it.
 *     Still set by {@code FX_IMPORT_ECB_BASE_URL}, the variable it had as {@code
 *     app.fx.import.ecb-base-url}
 */
@ConfigurationProperties(prefix = "app.fx.import.providers.ecb")
public record EcbFxRateProviderProperties(URI baseUrl) {

  public EcbFxRateProviderProperties {
    if (baseUrl == null) {
      throw new IllegalArgumentException("app.fx.import.providers.ecb.base-url is required");
    }
  }
}
