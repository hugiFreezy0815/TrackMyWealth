package com.trackmywealth.backend.service;

import static java.math.RoundingMode.HALF_UP;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.trackmywealth.backend.client.FxRateProvider;
import com.trackmywealth.backend.client.FxRateProviderDefinition;
import com.trackmywealth.backend.client.ProvidedFxRate;
import com.trackmywealth.backend.dto.CurrencyConversionResult;
import com.trackmywealth.backend.entity.FxRate;
import com.trackmywealth.backend.repository.FxRateRepository;
import com.trackmywealth.backend.testsupport.MutableClock;
import com.trackmywealth.backend.testsupport.TestClockConfig;
import java.math.BigDecimal;
import java.math.MathContext;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * #226: a provider whose hub currency is not the euro. A stub quoting every currency against CHF
 * (as the SNB would) is selected with {@code app.fx.import.provider} and is the only provider
 * loaded; the import stores its rates under its own source, derives the cross rates between the
 * currencies in use through CHF, and {@link FxRateService} chains a pair with no stored rate
 * through CHF. The ECB's behaviour is covered, unchanged, by {@link FxRateImportServiceTest} and
 * {@code FxRateServiceTest}.
 *
 * <p>"Today" is Wednesday 2026-09-16; with nothing booked the first import loads the six weekdays
 * from 2026-09-09 on.
 */
@Testcontainers
@SpringBootTest(
    properties = {
      "app.fx.import.enabled=true",
      "app.fx.import.provider=" + FxRateNonEuroHubTest.StubChfHubProvider.NAME,
      "app.fx.default-source=" + FxRateNonEuroHubTest.StubChfHubProvider.SOURCE,
      "spring.quartz.auto-startup=false"
    })
@Import({TestClockConfig.class, FxRateNonEuroHubTest.StubProviderConfig.class})
class FxRateNonEuroHubTest {

  @Container
  static PostgreSQLContainer<?> postgres =
      new PostgreSQLContainer<>("postgres:16")
          .withDatabaseName("trackmywealth")
          .withUsername("trackmywealth")
          .withPassword("trackmywealth");

  @DynamicPropertySource
  static void datasourceProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", postgres::getJdbcUrl);
    registry.add("spring.datasource.username", postgres::getUsername);
    registry.add("spring.datasource.password", postgres::getPassword);
  }

  private static final LocalDate TODAY = LocalDate.of(2026, 9, 16);
  private static final String SOURCE = StubChfHubProvider.SOURCE;
  private static final int WEEKDAYS = 6;
  private static final int PUBLISHED_PER_DAY = StubChfHubProvider.QUOTED.size();

  @Autowired FxRateImportService importService;
  @Autowired FxRateService fxRateService;
  @Autowired FxRateRepository fxRateRepository;
  @Autowired List<FxRateProvider> providers;
  @Autowired MutableClock clock;
  @Autowired JdbcTemplate jdbcTemplate;

  @BeforeEach
  void setUp() {
    clock.set(Instant.parse(TODAY + "T12:00:00Z"));
  }

  @AfterEach
  void cleanDatabase() {
    fxRateRepository.deleteAll();
    jdbcTemplate.update("DELETE FROM fx_rate_currency_in_use");
  }

  @Test
  void onlyTheNamedProviderIsLoadedAndTheImportStoresUnderItsSource() {
    assertThat(providers).singleElement().isInstanceOf(StubChfHubProvider.class);

    importService.importLatest();

    assertThat(jdbcTemplate.queryForList("SELECT DISTINCT source FROM fx_rate", String.class))
        .containsExactly(SOURCE);
    assertThat(fxRateRepository.count()).isEqualTo((long) PUBLISHED_PER_DAY * WEEKDAYS);
  }

  @Test
  void crossRatesBetweenTheCurrenciesInUseAreDerivedThroughTheHub() {
    useCurrencies("EUR", "USD");

    importService.importLatest();

    // Per day EUR/USD, USD/EUR, EUR/CHF and USD/CHF; CHF/x is published already, and JPY is not
    // in use.
    assertThat(fxRateRepository.count())
        .isEqualTo((long) PUBLISHED_PER_DAY * WEEKDAYS + 4L * WEEKDAYS);
    assertThat(derivedRate("EUR", "USD", TODAY))
        .isEqualByComparingTo(
            StubChfHubProvider.rate("USD", TODAY)
                .divide(StubChfHubProvider.rate("EUR", TODAY), 10, HALF_UP));
    assertThat(derivedRate("USD", "CHF", TODAY))
        .isEqualByComparingTo(
            BigDecimal.ONE.divide(StubChfHubProvider.rate("USD", TODAY), 10, HALF_UP));
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT count(*) FROM fx_rate WHERE derived AND base_currency = 'CHF'",
                Integer.class))
        .as("hub/x is published, never derived")
        .isZero();

    CurrencyConversionResult conversion =
        fxRateService.getConversionRate("EUR", "USD", TODAY, SOURCE);
    assertThat(conversion.rate()).isEqualByComparingTo(derivedRate("EUR", "USD", TODAY));
    assertThat(conversion.intermediateCurrency()).isEqualTo("CHF");
    assertThat(fxRateService.getConversionRate("USD", "CHF", TODAY, SOURCE).direct()).isTrue();
  }

  @Test
  void aCurrencyNewlyInUseGetsItsCrossRatesThroughTheHub() {
    importService.importLatest();

    useCurrencies("USD", "JPY");

    assertThat(importService.deriveCrossRatesForNewCurrencies())
        .as("USD/JPY, JPY/USD, USD/CHF and JPY/CHF on six weekdays")
        .isEqualTo(4 * WEEKDAYS);
    assertThat(derivedRate("USD", "JPY", TODAY))
        .isEqualByComparingTo(
            StubChfHubProvider.rate("JPY", TODAY)
                .divide(StubChfHubProvider.rate("USD", TODAY), 10, HALF_UP));
  }

  @Test
  void aPairWithoutAStoredRateIsChainedThroughTheHub() {
    importService.importLatest(); // nothing in use: only the published CHF/x rows

    CurrencyConversionResult conversion =
        fxRateService.getConversionRate("EUR", "JPY", TODAY, SOURCE);

    // EUR/CHF (the published CHF/EUR inverted) times CHF/JPY, rounded once.
    BigDecimal eurChf =
        BigDecimal.ONE.divide(StubChfHubProvider.rate("EUR", TODAY), MathContext.DECIMAL128);
    assertThat(conversion.rate())
        .isEqualByComparingTo(
            eurChf
                .multiply(StubChfHubProvider.rate("JPY", TODAY), MathContext.DECIMAL128)
                .setScale(10, HALF_UP));
    assertThat(conversion.direct()).isFalse();
    assertThat(conversion.intermediateCurrency()).isEqualTo("CHF");
  }

  @Test
  void manualRatesChainThroughTheSelectedNonEuroProviderHubFirst() {
    seedRate("CHF", "EUR", "1.05", "MANUAL");
    seedRate("CHF", "JPY", "170", "MANUAL");

    CurrencyConversionResult conversion =
        fxRateService.getConversionRate("EUR", "JPY", TODAY, "MANUAL");

    assertThat(conversion.intermediateCurrency()).isEqualTo("CHF");
    assertThat(conversion.rate())
        .isEqualByComparingTo(
            BigDecimal.ONE
                .divide(new BigDecimal("1.05"), MathContext.DECIMAL128)
                .multiply(new BigDecimal("170"), MathContext.DECIMAL128)
                .setScale(10, HALF_UP));
  }

  @Test
  void manualRatesEnteredAgainstTheEuroStillChainAfterTheSwitch() {
    // Product owner, 2026-10-04: a missing MANUAL pair is computed whenever any registered hub
    // chains it - here only the euro does, the selected provider's CHF does not.
    seedRate("EUR", "USD", "1.25", "MANUAL");
    seedRate("EUR", "JPY", "160", "MANUAL");

    CurrencyConversionResult conversion =
        fxRateService.getConversionRate("USD", "JPY", TODAY, "MANUAL");

    assertThat(conversion.intermediateCurrency()).isEqualTo("EUR");
    assertThat(conversion.rate()).isEqualByComparingTo("128.0000000000");
  }

  @Test
  void aManualPairNoHubChainsIsRefusedNamingEveryHubTried() {
    seedRate("EUR", "USD", "1.25", "MANUAL");

    assertThatThrownBy(() -> fxRateService.getConversionRate("USD", "JPY", TODAY, "MANUAL"))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("directly or via CHF or EUR.");
  }

  @Test
  void anUnregisteredSourceServesItsStoredPairsButNeverChains() {
    seedRate("EUR", "USD", "1.25", "OTHER");
    seedRate("EUR", "JPY", "160", "OTHER");

    assertThat(fxRateService.getConversionRate("EUR", "USD", TODAY, "OTHER").rate())
        .isEqualByComparingTo("1.25");
    assertThatThrownBy(() -> fxRateService.getConversionRate("USD", "JPY", TODAY, "OTHER"))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("no hub is registered for that source.");
  }

  @Test
  void anUnregisteredSourcesDerivedRowIsNotServed() {
    // A derived row's provenance is its source's hub; with no definition left for the source
    // there is none, so the row is refused rather than served as if it were published.
    seedRate("USD", "JPY", "128", "OTHER", true);

    assertThat(fxRateService.tryGetConversionRate("USD", "JPY", TODAY, "OTHER")).isEmpty();
    assertThat(fxRateService.tryGetConversionRate("JPY", "USD", TODAY, "OTHER")).isEmpty();
  }

  @Test
  void aDerivedManualRowIsPassedOverForTheChainThroughTheHubs() {
    // MANUAL has several hubs, so a derived row there names none it came through: the pair is
    // computed as if it were missing, never labelled with a hub it may not have used.
    seedRate("USD", "JPY", "999", "MANUAL", true);
    seedRate("CHF", "USD", "0.8", "MANUAL");
    seedRate("CHF", "JPY", "170", "MANUAL");

    CurrencyConversionResult conversion =
        fxRateService.getConversionRate("USD", "JPY", TODAY, "MANUAL");

    assertThat(conversion.intermediateCurrency()).isEqualTo("CHF");
    assertThat(conversion.rate()).isEqualByComparingTo("212.5");
  }

  @Test
  void aHistoricalProviderSourceKeepsItsOwnHubAfterTheActiveProviderChanges() {
    seedRate("EUR", "USD", "1.25", "ECB");
    seedRate("EUR", "JPY", "160", "ECB");

    CurrencyConversionResult conversion =
        fxRateService.getConversionRate("USD", "JPY", TODAY, "ECB");

    assertThat(conversion.intermediateCurrency()).isEqualTo("EUR");
    assertThat(conversion.rate()).isEqualByComparingTo("128.0000000000");
  }

  @Test
  void aPairNeitherStoredNorChainableIsRefusedNamingTheHub() {
    importService.importLatest();

    assertThatThrownBy(() -> fxRateService.getConversionRate("GBP", "USD", TODAY, SOURCE))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("directly or via CHF.");
  }

  private void seedRate(String base, String quote, String value, String source) {
    seedRate(base, quote, value, source, false);
  }

  private void seedRate(String base, String quote, String value, String source, boolean derived) {
    FxRate rate = new FxRate();
    rate.setDerived(derived);
    rate.setBaseCurrency(base);
    rate.setQuoteCurrency(quote);
    rate.setRateDate(TODAY);
    rate.setRate(new BigDecimal(value));
    rate.setSource(source);
    fxRateRepository.saveAndFlush(rate);
  }

  private void useCurrencies(String... currencies) {
    for (String currency : currencies) {
      jdbcTemplate.update(
          "INSERT INTO fx_rate_currency_in_use (currency) VALUES (?) ON CONFLICT DO NOTHING",
          currency);
    }
  }

  private BigDecimal derivedRate(String base, String quote, LocalDate date) {
    return jdbcTemplate.queryForObject(
        "SELECT rate FROM fx_rate WHERE base_currency = ? AND quote_currency = ?"
            + " AND rate_date = ? AND source = ? AND derived",
        BigDecimal.class,
        base,
        quote,
        date,
        SOURCE);
  }

  @TestConfiguration
  static class StubProviderConfig {
    @Bean
    FxRateProviderDefinition stubChfHubProviderDefinition() {
      return StubChfHubProvider.DEFINITION;
    }

    @Bean
    @ConditionalOnProperty(
        prefix = "app.fx.import",
        name = "provider",
        havingValue = StubChfHubProvider.NAME)
    StubChfHubProvider stubChfHubProvider() {
      return new StubChfHubProvider();
    }
  }

  /**
   * Publishes CHF/EUR, CHF/USD and CHF/JPY on every weekday, each a distinct value derived from its
   * date.
   */
  static class StubChfHubProvider implements FxRateProvider {

    static final String NAME = "stub-chf-hub";
    static final String SOURCE = "STUB_CHF_HUB";
    static final FxRateProviderDefinition DEFINITION =
        new FxRateProviderDefinition(NAME, SOURCE, "CHF");
    static final Map<String, BigDecimal> QUOTED =
        Map.of(
            "EUR", new BigDecimal("1.05"),
            "USD", new BigDecimal("1.2"),
            "JPY", new BigDecimal("170"));

    @Override
    public FxRateProviderDefinition definition() {
      return DEFINITION;
    }

    @Override
    public List<ProvidedFxRate> fetch(LocalDate from, LocalDate to) {
      List<ProvidedFxRate> rates = new ArrayList<>();
      from.datesUntil(to.plusDays(1))
          .filter(StubChfHubProvider::isWeekday)
          .forEach(
              date ->
                  QUOTED.keySet().stream()
                      .sorted()
                      .forEach(
                          currency ->
                              rates.add(
                                  new ProvidedFxRate(
                                      "CHF", currency, date, rate(currency, date)))));
      return rates;
    }

    static BigDecimal rate(String currency, LocalDate date) {
      return QUOTED.get(currency).add(new BigDecimal(date.getDayOfYear()).movePointLeft(4));
    }

    private static boolean isWeekday(LocalDate date) {
      return date.getDayOfWeek() != DayOfWeek.SATURDAY && date.getDayOfWeek() != DayOfWeek.SUNDAY;
    }
  }
}
