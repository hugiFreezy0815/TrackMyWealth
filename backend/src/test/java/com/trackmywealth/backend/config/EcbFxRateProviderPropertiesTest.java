package com.trackmywealth.backend.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.trackmywealth.backend.client.EcbFxRateProvider;
import java.io.IOException;
import java.net.URI;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.AbstractEnvironment;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.core.io.ClassPathResource;

/**
 * #226: the ECB's URL moved to {@code app.fx.import.providers.ecb.base-url}. Binds the real {@code
 * application.yml}, with environment variables supplied by the test only - the machine's own never
 * reach it - to show that {@code FX_IMPORT_ECB_BASE_URL} still sets it, and that a deployment still
 * setting the renamed {@code app.fx.import.ecb-base-url} is not silently ignored. Also pins the
 * file's default {@code app.fx.import.provider} to the ECB's name.
 */
class EcbFxRateProviderPropertiesTest {

  private static final String ECB_DATA_API = "https://data-api.ecb.europa.eu/service/data/EXR";

  @Test
  void withNothingSetItIsTheEcbDataApi() throws IOException {
    assertThat(bind(Map.of()).baseUrl()).isEqualTo(URI.create(ECB_DATA_API));
  }

  @Test
  void fxImportEcbBaseUrlSetsIt() throws IOException {
    assertThat(bind(Map.of("FX_IMPORT_ECB_BASE_URL", "https://variable.example/EXR")).baseUrl())
        .isEqualTo(URI.create("https://variable.example/EXR"));
  }

  @Test
  void theRenamedKeyStillSetsIt() throws IOException {
    // As an application.yml override or a command-line argument would set it.
    assertThat(
            bind(Map.of(), Map.of("app.fx.import.ecb-base-url", "https://legacy.example/EXR"))
                .baseUrl())
        .isEqualTo(URI.create("https://legacy.example/EXR"));
  }

  @Test
  void theRenamedKeyStillSetsItAsAnEnvironmentVariable() throws IOException {
    assertThat(bind(Map.of("APP_FX_IMPORT_ECB_BASE_URL", "https://legacy.example/EXR")).baseUrl())
        .isEqualTo(URI.create("https://legacy.example/EXR"));
  }

  @Test
  void fxImportEcbBaseUrlWinsOverTheRenamedKey() throws IOException {
    assertThat(
            bind(Map.of(
                    "FX_IMPORT_ECB_BASE_URL", "https://variable.example/EXR",
                    "APP_FX_IMPORT_ECB_BASE_URL", "https://legacy.example/EXR"))
                .baseUrl())
        .isEqualTo(URI.create("https://variable.example/EXR"));
  }

  @Test
  void withNothingSetTheProviderIsTheEcb() throws IOException {
    // application.yml states the default once more; it must name the constant the ECB client's
    // matchIfMissing uses (FxRateImportProperties' @DefaultValue is pinned to it in its own test).
    assertThat(environment(Map.of(), Map.of()).getProperty("app.fx.import.provider"))
        .isEqualTo(EcbFxRateProvider.PROVIDER_NAME);
  }

  @Test
  void aMissingBaseUrlIsRefused() {
    assertThatThrownBy(() -> new EcbFxRateProviderProperties(null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("app.fx.import.providers.ecb.base-url is required");
  }

  private static EcbFxRateProviderProperties bind(Map<String, Object> environmentVariables)
      throws IOException {
    return bind(environmentVariables, Map.of());
  }

  private static EcbFxRateProviderProperties bind(
      Map<String, Object> environmentVariables, Map<String, Object> properties) throws IOException {
    return Binder.get(environment(environmentVariables, properties))
        .bindOrCreate("app.fx.import.providers.ecb", EcbFxRateProviderProperties.class);
  }

  private static ConfigurableEnvironment environment(
      Map<String, Object> environmentVariables, Map<String, Object> properties) throws IOException {
    ConfigurableEnvironment environment = new AbstractEnvironment() {};
    environment.getPropertySources().addFirst(new MapPropertySource("test-properties", properties));
    environment
        .getPropertySources()
        .addLast(new SystemEnvironmentPropertySource("test-environment", environmentVariables));
    for (var source :
        new YamlPropertySourceLoader()
            .load("application.yml", new ClassPathResource("application.yml"))) {
      environment.getPropertySources().addLast(source);
    }
    ConfigurationPropertySources.attach(environment);
    return environment;
  }
}
