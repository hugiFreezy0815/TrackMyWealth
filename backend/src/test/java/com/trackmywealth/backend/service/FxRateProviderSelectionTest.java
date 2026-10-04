package com.trackmywealth.backend.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.trackmywealth.backend.client.EcbFxRateProvider;
import com.trackmywealth.backend.client.EcbFxRateProviderDefinitionConfig;
import com.trackmywealth.backend.client.FxRateProvider;
import com.trackmywealth.backend.client.FxRateProviderDefinition;
import com.trackmywealth.backend.client.ProvidedFxRate;
import com.trackmywealth.backend.config.EcbFxRateProviderProperties;
import com.trackmywealth.backend.config.FxRateImportProperties;
import com.trackmywealth.backend.repository.FxRateBatchRepository;
import com.trackmywealth.backend.repository.FxRateHistoryRequirementRepository;
import com.trackmywealth.backend.repository.FxRateRepository;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * #226: {@code app.fx.import.provider} loads exactly the provider it names - the ECB when unset -
 * and a name no provider answers to stops the start with a message naming the valid values. Runs
 * the real conditions and the real {@link FxRateImportService} constructor; its database
 * collaborators are mocks, since choosing a provider never reaches them.
 */
class FxRateProviderSelectionTest {

  private final ApplicationContextRunner runner =
      new ApplicationContextRunner()
          .withUserConfiguration(
              Binding.class,
              StubProviderConfig.class,
              EcbFxRateProviderDefinitionConfig.class,
              EcbFxRateProvider.class)
          .withBean(FxRateProviderCatalogService.class)
          .withBean(FxRateImportService.class)
          .withBean(FxRateRepository.class, () -> mock(FxRateRepository.class))
          .withBean(FxRateBatchRepository.class, () -> mock(FxRateBatchRepository.class))
          .withBean(
              FxRateHistoryRequirementRepository.class,
              () -> mock(FxRateHistoryRequirementRepository.class))
          .withBean(BusinessDateService.class, () -> mock(BusinessDateService.class))
          .withBean(PlatformTransactionManager.class, () -> mock(PlatformTransactionManager.class))
          .withPropertyValues(
              "app.fx.import.enabled=false",
              "app.fx.import.import-cron=0 0 0/2 * * ?",
              "app.fx.import.import-cron-zone=Europe/Berlin",
              "app.fx.import.history-check-interval=5m",
              "app.fx.import.connect-timeout=5s",
              "app.fx.import.read-timeout=60s",
              "app.fx.import.on-demand-read-timeout=20s",
              "app.fx.import.on-demand-retry-after=1h",
              "app.fx.import.providers.ecb.base-url=https://ecb.example/EXR",
              "app.fx.default-source=ECB");

  @Test
  void withoutAProviderSettingTheEcbIsLoadedAsToday() {
    runner.run(
        context -> {
          assertThat(context).hasNotFailed();
          assertThat(context).hasSingleBean(FxRateProvider.class);
          assertThat(context).hasSingleBean(EcbFxRateProvider.class);
          FxRateImportService importService = context.getBean(FxRateImportService.class);
          assertThat(importService.source()).isEqualTo("ECB");
          assertThat(importService.hubCurrency()).isEqualTo("EUR");
        });
  }

  @Test
  void theEcbIsSelectedByNameWhateverItsCase() {
    for (String name : List.of("ecb", "ECB")) {
      runner
          .withPropertyValues("app.fx.import.provider=" + name)
          .run(context -> assertThat(context).hasSingleBean(EcbFxRateProvider.class));
    }
  }

  @Test
  void anotherProviderNamedIsTheOnlyOneLoadedAndTheImportStoresUnderItsSource() {
    runner
        .withPropertyValues(
            "app.fx.import.provider=" + StubChfProvider.NAME,
            "app.fx.default-source=" + StubChfProvider.SOURCE)
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              assertThat(context).hasSingleBean(FxRateProvider.class);
              assertThat(context).doesNotHaveBean(EcbFxRateProvider.class);
              FxRateImportService importService = context.getBean(FxRateImportService.class);
              assertThat(importService.source()).isEqualTo(StubChfProvider.SOURCE);
              assertThat(importService.hubCurrency()).isEqualTo("CHF");
            });
  }

  @Test
  void anUnknownProviderNameStopsTheStartNamingTheValidValues() {
    runner
        .withPropertyValues("app.fx.import.provider=bundesbank")
        .run(
            context -> {
              assertThat(context).hasFailed();
              assertThat(context)
                  .getFailure()
                  .rootCause()
                  .isInstanceOf(IllegalStateException.class)
                  .hasMessage(
                      "app.fx.import.provider (FX_IMPORT_PROVIDER) 'bundesbank' names no FX rate"
                          + " provider; valid values: ecb, stub-chf, stub-mismatch");
            });
  }

  @Test
  void aProviderAndDifferentNonManualDefaultSourceFailStartup() {
    runner
        .withPropertyValues(
            "app.fx.import.provider=" + StubChfProvider.NAME, "app.fx.default-source=ECB")
        .run(
            context -> {
              assertThat(context).hasFailed();
              assertThat(context)
                  .getFailure()
                  .rootCause()
                  .isInstanceOf(IllegalStateException.class)
                  .hasMessageContaining(
                      "must be MANUAL or the selected FX provider source 'STUB_CHF'");
            });
  }

  @Test
  void manualDefaultSourceTriesTheSelectedProvidersHubFirst() {
    runner
        .withPropertyValues(
            "app.fx.import.provider=" + StubChfProvider.NAME, "app.fx.default-source=MANUAL")
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              FxRateProviderCatalogService catalog =
                  context.getBean(FxRateProviderCatalogService.class);
              assertThat(catalog.hubCurrenciesForSource("MANUAL")).containsExactly("CHF", "EUR");
            });
  }

  @Test
  void aClientWiredToAnotherProvidersNameStopsTheStart() {
    runner
        .withPropertyValues(
            "app.fx.import.provider=" + MismatchedProvider.NAME,
            "app.fx.default-source=" + MismatchedProvider.SOURCE)
        .run(
            context -> {
              assertThat(context).hasFailed();
              assertThat(context)
                  .getFailure()
                  .rootCause()
                  .isInstanceOf(IllegalStateException.class)
                  .hasMessage(
                      "FX provider 'stub-mismatch' is selected, but the loaded FxRateProvider"
                          + " client is "
                          + StubChfProvider.DEFINITION);
            });
  }

  @Test
  void aRegisteredProviderWithoutAClientStopsTheStart() {
    // A definition whose client is missing, or conditional on another name: the catalog accepts
    // the name, so the import service is what notices that nothing answers to it.
    runner
        .withBean(
            "clientlessDefinition",
            FxRateProviderDefinition.class,
            () -> new FxRateProviderDefinition("stub-clientless", "STUB_CLIENTLESS", "CHF"))
        .withPropertyValues(
            "app.fx.import.provider=stub-clientless", "app.fx.default-source=STUB_CLIENTLESS")
        .run(
            context -> {
              assertThat(context).hasFailed();
              assertThat(context)
                  .getFailure()
                  .rootCause()
                  .isInstanceOf(IllegalStateException.class)
                  .hasMessage(
                      "Selected FX provider 'stub-clientless' has no FxRateProvider client bean");
            });
  }

  @Configuration(proxyBeanMethods = false)
  @EnableConfigurationProperties({FxRateImportProperties.class, EcbFxRateProviderProperties.class})
  static class Binding {}

  // A second implementation, conditional on its own name exactly as the ECB's is.
  @Configuration(proxyBeanMethods = false)
  static class StubProviderConfig {
    @Bean
    FxRateProviderDefinition stubChfProviderDefinition() {
      return StubChfProvider.DEFINITION;
    }

    @Bean
    @ConditionalOnProperty(
        prefix = "app.fx.import",
        name = "provider",
        havingValue = StubChfProvider.NAME)
    StubChfProvider stubChfProvider() {
      return new StubChfProvider();
    }

    @Bean
    FxRateProviderDefinition mismatchedProviderDefinition() {
      return new FxRateProviderDefinition(
          MismatchedProvider.NAME, MismatchedProvider.SOURCE, "CHF");
    }

    @Bean
    @ConditionalOnProperty(
        prefix = "app.fx.import",
        name = "provider",
        havingValue = MismatchedProvider.NAME)
    MismatchedProvider mismatchedProvider() {
      return new MismatchedProvider();
    }
  }

  // Loaded under its own name, but its client is a copy of another provider's.
  static class MismatchedProvider implements FxRateProvider {

    static final String NAME = "stub-mismatch";
    static final String SOURCE = "STUB_MISMATCH";

    @Override
    public FxRateProviderDefinition definition() {
      return StubChfProvider.DEFINITION;
    }

    @Override
    public List<ProvidedFxRate> fetch(LocalDate from, LocalDate to) {
      return List.of();
    }
  }

  static class StubChfProvider implements FxRateProvider {

    static final String NAME = "stub-chf";
    static final String SOURCE = "STUB_CHF";
    static final FxRateProviderDefinition DEFINITION =
        new FxRateProviderDefinition(NAME, SOURCE, "CHF");

    @Override
    public FxRateProviderDefinition definition() {
      return DEFINITION;
    }

    @Override
    public List<ProvidedFxRate> fetch(LocalDate from, LocalDate to) {
      return List.of();
    }
  }
}
