package com.trackmywealth.backend.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.trackmywealth.backend.client.EcbFxRateProvider;
import java.time.Duration;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;

/** #223: a misconfigured FX import fails the start, naming the property, rather than every run. */
class FxRateImportPropertiesTest {

  private static final String ECB = "ecb";
  private static final ZoneId BERLIN = ZoneId.of("Europe/Berlin");
  private static final Duration HOUR = Duration.ofHours(1);

  @Test
  void theDefaultProviderIsTheEcb() {
    // #226: a literal in config, since config must not depend on client - pinned here instead.
    assertThat(FxRateImportProperties.DEFAULT_PROVIDER).isEqualTo(EcbFxRateProvider.PROVIDER_NAME);
  }

  @Test
  void aCompleteConfigurationIsAccepted() {
    assertThatCode(
            () ->
                new FxRateImportProperties(
                    true, ECB, "0 0 0/2 * * ?", BERLIN, HOUR, HOUR, HOUR, HOUR, HOUR))
        .doesNotThrowAnyException();
  }

  @Test
  void aMissingOrNonPositiveSettingIsRejectedByName() {
    assertThatThrownBy(
            () ->
                new FxRateImportProperties(
                    true, " ", "0 0 0/2 * * ?", BERLIN, HOUR, HOUR, HOUR, HOUR, HOUR))
        .hasMessage("app.fx.import.provider is required");
    assertThatThrownBy(
            () -> new FxRateImportProperties(true, ECB, " ", BERLIN, HOUR, HOUR, HOUR, HOUR, HOUR))
        .hasMessage("app.fx.import.import-cron is required");
    assertThatThrownBy(
            () ->
                new FxRateImportProperties(
                    true, ECB, "0 0 0/2 * * ?", null, HOUR, HOUR, HOUR, HOUR, HOUR))
        .hasMessage("app.fx.import.import-cron-zone is required");
    assertThatThrownBy(
            () ->
                new FxRateImportProperties(
                    true, ECB, "0 0 0/2 * * ?", BERLIN, Duration.ZERO, HOUR, HOUR, HOUR, HOUR))
        .hasMessage("app.fx.import.history-check-interval must be positive");
    assertThatThrownBy(
            () ->
                new FxRateImportProperties(
                    true,
                    ECB,
                    "0 0 0/2 * * ?",
                    BERLIN,
                    HOUR,
                    HOUR,
                    HOUR,
                    HOUR,
                    Duration.ofSeconds(-1)))
        .hasMessage("app.fx.import.on-demand-retry-after must be positive");
    assertThatThrownBy(
            () ->
                new FxRateImportProperties(
                    true, ECB, "0 0 0/2 * * ?", BERLIN, HOUR, HOUR, HOUR, Duration.ZERO, HOUR))
        .hasMessage("app.fx.import.on-demand-read-timeout must be positive");
  }
}
