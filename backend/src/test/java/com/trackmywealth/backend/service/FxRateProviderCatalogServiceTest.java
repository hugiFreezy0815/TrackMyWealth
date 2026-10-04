package com.trackmywealth.backend.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.trackmywealth.backend.client.EcbFxRateProvider;
import com.trackmywealth.backend.client.FxRateProviderDefinition;
import com.trackmywealth.backend.config.FxRateImportProperties;
import java.time.Duration;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * #226: the catalog's startup checks and its source-to-hub resolution, without a Spring context.
 * The context wiring is covered by {@link FxRateProviderSelectionTest}.
 */
class FxRateProviderCatalogServiceTest {

  private static final FxRateProviderDefinition ECB = EcbFxRateProvider.ECB_DEFINITION;
  private static final FxRateProviderDefinition SNB =
      new FxRateProviderDefinition("snb", "SNB", "CHF");
  private static final FxRateProviderDefinition SNB_SHADOW =
      new FxRateProviderDefinition("snb-shadow", "SNB_SHADOW", "CHF");

  @Test
  void noRegisteredDefinitionStopsTheStart() {
    assertThatThrownBy(() -> catalog(List.of(), "ecb", "ECB"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("No FX rate provider definitions are registered");
  }

  @Test
  void twoDefinitionsWithTheSameNameWhateverItsCaseStopTheStart() {
    FxRateProviderDefinition clash = new FxRateProviderDefinition("ECB", "ECB_2", "EUR");

    assertThatThrownBy(() -> catalog(List.of(ECB, clash), "ecb", "ECB"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("FX provider name 'ecb' is declared by both 'ECB' and 'ecb'");
  }

  @Test
  void twoDefinitionsWithTheSameSourceStopTheStart() {
    FxRateProviderDefinition clash = new FxRateProviderDefinition("ecb-mirror", "ECB", "EUR");

    assertThatThrownBy(() -> catalog(List.of(ECB, clash), "ecb", "ECB"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("FX provider source 'ECB' is declared by both 'ecb' and 'ecb-mirror'");
  }

  @Test
  void aProviderDeclaringTheManualSourceStopsTheStart() {
    // Even when it is not the selected one: its definition alone would give MANUAL its own hub.
    FxRateProviderDefinition manual = new FxRateProviderDefinition("by-hand", "MANUAL", "CHF");

    assertThatThrownBy(() -> catalog(List.of(ECB, manual), "ecb", "ECB"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage(
            "FX provider 'by-hand' declares the source MANUAL, which is reserved for rates entered"
                + " by hand");
  }

  @Test
  void anUnknownProviderNameStopsTheStartNamingTheValidValuesSorted() {
    assertThatThrownBy(() -> catalog(List.of(SNB, ECB), "bundesbank", "MANUAL"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage(
            "app.fx.import.provider (FX_IMPORT_PROVIDER) 'bundesbank' names no FX rate provider;"
                + " valid values: ecb, snb");
  }

  @Test
  void theProviderNameIsCaseInsensitive() {
    assertThat(catalog(List.of(ECB, SNB), "SNB", "SNB").selected()).isEqualTo(SNB);
  }

  @Test
  void theDefaultSourceIsCaseSensitive() {
    assertThatThrownBy(() -> catalog(List.of(ECB), "ecb", "ecb"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage(
            "app.fx.default-source (FX_DEFAULT_SOURCE) 'ecb' must be MANUAL or the selected FX"
                + " provider source 'ECB' (case-sensitive) when app.fx.import.provider is 'ecb'");
    assertThatThrownBy(() -> catalog(List.of(ECB), "ecb", "manual"))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void aProviderSourceHasOnlyItsOwnHubWhicheverProviderIsSelected() {
    FxRateProviderCatalogService catalog = catalog(List.of(ECB, SNB), "snb", "SNB");

    assertThat(catalog.hubCurrenciesForSource("ECB")).containsExactly("EUR");
    assertThat(catalog.hubCurrenciesForSource("SNB")).containsExactly("CHF");
  }

  @Test
  void manualTriesTheSelectedHubFirstThenEveryOtherRegisteredHubOnce() {
    assertThat(
            catalog(List.of(ECB, SNB, SNB_SHADOW), "snb", "MANUAL")
                .hubCurrenciesForSource("MANUAL"))
        .containsExactly("CHF", "EUR");
    assertThat(
            catalog(List.of(ECB, SNB, SNB_SHADOW), "ecb", "MANUAL")
                .hubCurrenciesForSource("MANUAL"))
        .containsExactly("EUR", "CHF");
    assertThat(catalog(List.of(ECB), "ecb", "ECB").hubCurrenciesForSource("MANUAL"))
        .containsExactly("EUR");
  }

  @Test
  void anUnregisteredSourceHasNoHub() {
    assertThat(catalog(List.of(ECB), "ecb", "ECB").hubCurrenciesForSource("OTHER")).isEmpty();
  }

  @Test
  void aDefinitionNeedsANameASourceAndAnIsoHubCurrency() {
    assertThatThrownBy(() -> new FxRateProviderDefinition(" ", "S", "EUR"))
        .hasMessage("FX provider name is required");
    assertThatThrownBy(() -> new FxRateProviderDefinition("n", null, "EUR"))
        .hasMessage("FX provider source is required");
    assertThatThrownBy(() -> new FxRateProviderDefinition("n", "S", ""))
        .hasMessage("FX provider hub currency is required");
    assertThatThrownBy(() -> new FxRateProviderDefinition("n", "S", "XYZ"))
        .hasMessage("FX provider hub currency 'XYZ' is not an ISO 4217 currency");
  }

  private static FxRateProviderCatalogService catalog(
      List<FxRateProviderDefinition> definitions, String provider, String defaultSource) {
    return new FxRateProviderCatalogService(definitions, properties(provider), defaultSource);
  }

  private static FxRateImportProperties properties(String provider) {
    Duration hour = Duration.ofHours(1);
    return new FxRateImportProperties(
        false, provider, "0 0 0/2 * * ?", ZoneId.of("Europe/Berlin"), hour, hour, hour, hour, hour);
  }
}
