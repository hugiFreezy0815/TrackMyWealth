package com.trackmywealth.backend.config;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.time.Duration;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;

/** #223: a misconfigured FX import fails the start, naming the property, rather than every run. */
class FxRateImportPropertiesTest {

  private static final URI URL = URI.create("https://data-api.ecb.europa.eu/service/data/EXR");
  private static final ZoneId BERLIN = ZoneId.of("Europe/Berlin");
  private static final Duration HOUR = Duration.ofHours(1);

  @Test
  void aCompleteConfigurationIsAccepted() {
    assertThatCode(
            () ->
                new FxRateImportProperties(
                    true, URL, "0 30 16 * * ?", BERLIN, HOUR, HOUR, HOUR, HOUR))
        .doesNotThrowAnyException();
  }

  @Test
  void aMissingOrNonPositiveSettingIsRejectedByName() {
    assertThatThrownBy(
            () ->
                new FxRateImportProperties(
                    true, null, "0 30 16 * * ?", BERLIN, HOUR, HOUR, HOUR, HOUR))
        .hasMessage("app.fx.import.ecb-base-url is required");
    assertThatThrownBy(
            () -> new FxRateImportProperties(true, URL, " ", BERLIN, HOUR, HOUR, HOUR, HOUR))
        .hasMessage("app.fx.import.daily-cron is required");
    assertThatThrownBy(
            () ->
                new FxRateImportProperties(
                    true, URL, "0 30 16 * * ?", null, HOUR, HOUR, HOUR, HOUR))
        .hasMessage("app.fx.import.daily-cron-zone is required");
    assertThatThrownBy(
            () ->
                new FxRateImportProperties(
                    true, URL, "0 30 16 * * ?", BERLIN, Duration.ZERO, HOUR, HOUR, HOUR))
        .hasMessage("app.fx.import.history-check-interval must be positive");
    assertThatThrownBy(
            () ->
                new FxRateImportProperties(
                    true, URL, "0 30 16 * * ?", BERLIN, HOUR, HOUR, HOUR, Duration.ofSeconds(-1)))
        .hasMessage("app.fx.import.on-demand-retry-after must be positive");
  }
}
