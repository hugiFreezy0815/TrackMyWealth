package com.trackmywealth.backend.config;

import java.net.URI;
import java.time.Duration;
import java.time.ZoneId;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The FX rate import (US-06-04, #223): where rates come from, when the scheduled jobs run, and how
 * the on-demand fetch is bounded.
 *
 * @param enabled {@code false} turns off every provider call - the scheduled jobs are not
 *     registered and a missing rate is never fetched. Tests run with it off so none of them depends
 *     on the network.
 * @param ecbBaseUrl the ECB data API's EXR dataflow; the daily euro reference rates are read from
 *     it
 * @param importCron when the import runs (Quartz cron, seconds first) - every two hours by default
 *     (product owner, 2026-10-02). The ECB publishes once, around 16:00 CET on TARGET business
 *     days; a run with nothing new costs one small provider call.
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
    URI ecbBaseUrl,
    String importCron,
    ZoneId importCronZone,
    Duration historyCheckInterval,
    Duration connectTimeout,
    Duration readTimeout,
    Duration onDemandReadTimeout,
    Duration onDemandRetryAfter) {

  private static final String PREFIX = "app.fx.import.";

  public FxRateImportProperties {
    requireNonNull(ecbBaseUrl, "ecb-base-url");
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
