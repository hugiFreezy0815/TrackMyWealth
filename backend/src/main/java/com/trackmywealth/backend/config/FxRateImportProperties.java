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
 * @param dailyCron when the daily import runs (Quartz cron, seconds first). The ECB publishes
 *     around 16:00 CET on TARGET business days.
 * @param dailyCronZone the zone {@code dailyCron} is read in
 * @param historyCheckInterval how often the backfill checks whether an older transaction now needs
 *     older rates; without such a need the check makes no provider call
 * @param connectTimeout connection timeout of a provider call
 * @param readTimeout read timeout of a provider call; a backfill of several years is one response
 * @param onDemandRetryAfter how long a fetch-on-missing that found nothing is not repeated for the
 *     same date, so a date the provider has no rate for costs one call, not one per conversion
 */
@ConfigurationProperties(prefix = "app.fx.import")
public record FxRateImportProperties(
    boolean enabled,
    URI ecbBaseUrl,
    String dailyCron,
    ZoneId dailyCronZone,
    Duration historyCheckInterval,
    Duration connectTimeout,
    Duration readTimeout,
    Duration onDemandRetryAfter) {

  private static final String PREFIX = "app.fx.import.";

  public FxRateImportProperties {
    requireNonNull(ecbBaseUrl, "ecb-base-url");
    requireNonBlank(dailyCron, "daily-cron");
    requireNonNull(dailyCronZone, "daily-cron-zone");
    requirePositive(historyCheckInterval, "history-check-interval");
    requirePositive(connectTimeout, "connect-timeout");
    requirePositive(readTimeout, "read-timeout");
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
