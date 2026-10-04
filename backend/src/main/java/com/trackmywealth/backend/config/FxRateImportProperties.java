package com.trackmywealth.backend.config;

import java.time.Duration;
import java.time.ZoneId;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * The FX rate import (US-06-04, #223): which provider rates come from, when the scheduled jobs run,
 * and how the on-demand fetch is bounded.
 *
 * @param enabled {@code false} turns off every provider call - the scheduled jobs are not
 *     registered and a missing rate is never fetched. Tests run with it off so none of them depends
 *     on the network.
 * @param provider which {@code FxRateProvider} the import uses (#226): {@code ecb} by default. Each
 *     provider is loaded only under its own name and has its own settings block under {@code
 *     app.fx.import.providers}; a name no provider answers to fails the start
 * @param importCron when the import runs (Quartz cron, seconds first) - every two hours by default
 *     (product owner, 2026-10-02). The ECB publishes once, around 16:00 CET on TARGET business
 *     days; a run with nothing new costs one small provider call. Only the default: an interval an
 *     administrator set (US-06-07, {@code FxImportScheduleService}) wins over it at every start.
 * @param importCronZone the zone {@code importCron} is read in
 * @param historyCheckInterval how often the backfill checks whether an older transaction now needs
 *     older rates; without such a need the check makes no provider call
 * @param connectTimeout connection timeout of a provider call
 * @param readTimeout read timeout of a background provider call; a year of rates is one response
 * @param onDemandReadTimeout read timeout of the one provider call a user's request waits for - a
 *     transaction created for a date the stored rates do not reach yet
 * @param onDemandRetryAfter how long a fetch-on-missing that found nothing is not repeated for the
 *     same date, so a date the provider has no rate for costs one call, not one per conversion
 */
@ConfigurationProperties(prefix = "app.fx.import")
public record FxRateImportProperties(
    boolean enabled,
    // Unset means the ECB. A literal, not EcbFxRateProvider.PROVIDER_NAME: config must not depend
    // on client (ArchitectureTest), and FxRateImportPropertiesTest pins the two to each other, as
    // EcbFxRateProviderPropertiesTest pins application.yml's default. Blank is an error.
    @DefaultValue(FxRateImportProperties.DEFAULT_PROVIDER) String provider,
    String importCron,
    ZoneId importCronZone,
    Duration historyCheckInterval,
    Duration connectTimeout,
    Duration readTimeout,
    Duration onDemandReadTimeout,
    Duration onDemandRetryAfter) {

  /** The provider used when {@code app.fx.import.provider} is unset: the ECB's name (#226). */
  public static final String DEFAULT_PROVIDER = "ecb";

  private static final String PREFIX = "app.fx.import.";

  public FxRateImportProperties {
    requireNonBlank(provider, "provider");
    requireNonBlank(importCron, "import-cron");
    requireNonNull(importCronZone, "import-cron-zone");
    requirePositive(historyCheckInterval, "history-check-interval");
    requirePositive(connectTimeout, "connect-timeout");
    requirePositive(readTimeout, "read-timeout");
    requirePositive(onDemandReadTimeout, "on-demand-read-timeout");
    requirePositive(onDemandRetryAfter, "on-demand-retry-after");
  }

  private static void requireNonNull(Object value, String name) {
    if (value == null) {
      throw new IllegalArgumentException(PREFIX + name + " is required");
    }
  }

  private static void requireNonBlank(String value, String name) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(PREFIX + name + " is required");
    }
  }

  private static void requirePositive(Duration value, String name) {
    if (value == null || value.isNegative() || value.isZero()) {
      throw new IllegalArgumentException(PREFIX + name + " must be positive");
    }
  }
}
