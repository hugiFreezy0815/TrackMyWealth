package com.trackmywealth.backend.service;

import com.trackmywealth.backend.client.FxRateProviderDefinition;
import com.trackmywealth.backend.config.FxRateImportProperties;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Catalog of selectable FX providers (#226).
 *
 * <p>Definitions are unconditional even though provider clients are conditional, so an invalid
 * provider setting can name every valid value and a stored source keeps its original hub after an
 * operator switches providers.
 *
 * <p>It also checks at startup that {@code app.fx.default-source} reads what the selected provider
 * stores, or {@code MANUAL}: a provider switch must not silently keep reading an old source. Both
 * the source and {@code MANUAL} are compared case-sensitively, as {@code fx_rate.source} is.
 *
 * <p>{@code MANUAL} has no provider of its own. A pair missing from it is chained through the
 * selected provider's hub first and then through every other registered provider's hub (product
 * owner, 2026-10-04: missing pairs are computed), so hand-entered {@code EUR/x} rates keep chaining
 * after a switch to a provider with another hub.
 */
@Service
public class FxRateProviderCatalogService {

  public static final String MANUAL_SOURCE = "MANUAL";

  private final Map<String, FxRateProviderDefinition> byName;
  private final Map<String, FxRateProviderDefinition> bySource;
  private final FxRateProviderDefinition selectedDefinition;
  // Asked for on every MANUAL conversion, so built once.
  private final List<String> manualHubCurrencies;

  public FxRateProviderCatalogService(
      List<FxRateProviderDefinition> definitions,
      FxRateImportProperties properties,
      @Value("${app.fx.default-source}") String defaultSource) {
    if (definitions.isEmpty()) {
      throw new IllegalStateException("No FX rate provider definitions are registered");
    }
    // MANUAL holds hand-entered rates and chains through every hub; a provider storing (and
    // deriving cross rates) under it would mix its rows into them and give MANUAL a hub of its own.
    for (FxRateProviderDefinition definition : definitions) {
      if (MANUAL_SOURCE.equals(definition.source())) {
        throw new IllegalStateException(
            "FX provider '"
                + definition.name()
                + "' declares the source "
                + MANUAL_SOURCE
                + ", which is reserved for rates entered by hand");
      }
    }
    this.byName = uniqueIndex(definitions, definition -> normalizeName(definition.name()), "name");
    this.bySource = uniqueIndex(definitions, FxRateProviderDefinition::source, "source");
    this.selectedDefinition = byName.get(normalizeName(properties.provider()));
    if (selectedDefinition == null) {
      throw new IllegalStateException(
          "app.fx.import.provider (FX_IMPORT_PROVIDER) '"
              + properties.provider()
              + "' names no FX rate provider; valid values: "
              + String.join(", ", sortedNames(byName)));
    }
    if (!MANUAL_SOURCE.equals(defaultSource)
        && !selectedDefinition.source().equals(defaultSource)) {
      throw new IllegalStateException(
          "app.fx.default-source (FX_DEFAULT_SOURCE) '"
              + defaultSource
              + "' must be MANUAL or the selected FX provider source '"
              + selectedDefinition.source()
              + "' (case-sensitive) when app.fx.import.provider is '"
              + properties.provider()
              + "'");
    }
    Set<String> manualHubs = new LinkedHashSet<>();
    manualHubs.add(selectedDefinition.hubCurrency());
    bySource.values().stream()
        .map(FxRateProviderDefinition::hubCurrency)
        .sorted()
        .forEach(manualHubs::add);
    this.manualHubCurrencies = List.copyOf(manualHubs);
  }

  /** The definition of the provider {@code app.fx.import.provider} names. */
  public FxRateProviderDefinition selected() {
    return selectedDefinition;
  }

  public List<String> validProviderNames() {
    return sortedNames(byName);
  }

  /**
   * The hubs a pair missing from {@code source} may be chained through, in the order to try them. A
   * provider source has exactly its own hub, independent of which provider is selected now; {@code
   * MANUAL} has the selected provider's hub first, then the other registered hubs; an unregistered
   * source has none.
   */
  public List<String> hubCurrenciesForSource(String source) {
    if (MANUAL_SOURCE.equals(source)) {
      return manualHubCurrencies;
    }
    FxRateProviderDefinition definition = bySource.get(source);
    return definition == null ? List.of() : List.of(definition.hubCurrency());
  }

  // Static, so the constructor's error message can use it without calling an overridable method.
  private static List<String> sortedNames(Map<String, FxRateProviderDefinition> byName) {
    return byName.values().stream().map(FxRateProviderDefinition::name).sorted().toList();
  }

  private static String normalizeName(String name) {
    return name.toLowerCase(Locale.ROOT);
  }

  private static Map<String, FxRateProviderDefinition> uniqueIndex(
      List<FxRateProviderDefinition> definitions,
      Function<FxRateProviderDefinition, String> key,
      String label) {
    Map<String, FxRateProviderDefinition> index = new LinkedHashMap<>();
    for (FxRateProviderDefinition definition :
        definitions.stream()
            .sorted(Comparator.comparing(FxRateProviderDefinition::name))
            .toList()) {
      FxRateProviderDefinition previous = index.putIfAbsent(key.apply(definition), definition);
      if (previous != null) {
        throw new IllegalStateException(
            "FX provider "
                + label
                + " '"
                + key.apply(definition)
                + "' is declared by both '"
                + previous.name()
                + "' and '"
                + definition.name()
                + "'");
      }
    }
    return index;
  }
}
