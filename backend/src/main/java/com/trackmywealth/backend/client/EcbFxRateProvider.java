package com.trackmywealth.backend.client;

import com.trackmywealth.backend.config.EcbFxRateProviderProperties;
import com.trackmywealth.backend.config.FxRateImportProperties;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Currency;
import java.util.List;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * The ECB's daily euro reference rates (US-06-04, #223): free, no API key, published around 16:00
 * CET on TARGET business days, every series against the euro. Read from the ECB data API's {@code
 * EXR} dataflow ({@value #SERIES_KEY}: daily, every currency, against EUR, spot, average) as CSV,
 * which answers a single day and a multi-year backfill with the same request.
 *
 * <p>A row's {@code OBS_VALUE} is how many units of {@code CURRENCY} one euro buys, so it is stored
 * as {@code EUR/<CURRENCY>}. Every other pair is derived from these by {@code FxRateService}.
 *
 * <p>The request carries only the series key and the date range - no user or workspace data.
 *
 * <p>#226: the default provider - loaded when {@code app.fx.import.provider} is {@value
 * #PROVIDER_NAME} or unset; its settings are under {@code app.fx.import.providers.ecb}.
 */
@Component
@ConditionalOnProperty(
    prefix = "app.fx.import",
    name = "provider",
    havingValue = EcbFxRateProvider.PROVIDER_NAME,
    matchIfMissing = true)
public class EcbFxRateProvider implements FxRateProvider {

  /** The {@code app.fx.import.provider} value that selects this provider. */
  public static final String PROVIDER_NAME = "ecb";

  public static final String ECB_SOURCE = "ECB";

  /**
   * The ECB's registered metadata (#226), the one place its name, source and hub are stated -
   * {@code EcbFxRateProviderDefinitionConfig} registers it whether or not this client is selected.
   */
  public static final FxRateProviderDefinition ECB_DEFINITION =
      new FxRateProviderDefinition(PROVIDER_NAME, ECB_SOURCE, "EUR");

  private static final Logger LOG = LoggerFactory.getLogger(EcbFxRateProvider.class);

  private static final String SERIES_KEY = "D..EUR.SP00.A";
  private static final String EURO = "EUR";
  // fx_rate.rate is NUMERIC(20,10): at most ten integer digits.
  private static final BigDecimal MAX_RATE = BigDecimal.TEN.pow(10);
  // How much of an unreadable answer an error message quotes.
  private static final int MAX_QUOTED_CHARS = 120;

  private final RestClient restClient;
  // An on-demand call gets what is left of its request's budget as timeout, so its client is
  // built per call, over one shared HttpClient. Such calls are rare: a transaction dated before
  // every stored rate.
  private final Function<Duration, RestClient> onDemandRestClients;

  @Autowired
  public EcbFxRateProvider(
      FxRateImportProperties properties, EcbFxRateProviderProperties ecbProperties) {
    this(
        ecbProperties.baseUrl(),
        properties.readTimeout(),
        HttpClient.newBuilder().connectTimeout(properties.connectTimeout()).build());
  }

  private EcbFxRateProvider(URI baseUrl, Duration readTimeout, HttpClient httpClient) {
    this(
        buildRestClient(baseUrl, httpClient, readTimeout),
        timeout -> buildRestClient(baseUrl, httpClient, timeout));
  }

  EcbFxRateProvider(RestClient restClient) {
    this(restClient, timeout -> restClient);
  }

  private EcbFxRateProvider(
      RestClient restClient, Function<Duration, RestClient> onDemandRestClients) {
    this.restClient = restClient;
    this.onDemandRestClients = onDemandRestClients;
  }

  @Override
  public FxRateProviderDefinition definition() {
    return ECB_DEFINITION;
  }

  @Override
  public List<ProvidedFxRate> fetch(LocalDate from, LocalDate to) {
    return fetch(restClient, from, to);
  }

  @Override
  public List<ProvidedFxRate> fetchOnDemand(LocalDate from, LocalDate to, Duration timeout) {
    return fetch(onDemandRestClients.apply(timeout), from, to);
  }

  private static List<ProvidedFxRate> fetch(RestClient client, LocalDate from, LocalDate to) {
    if (from.isAfter(to)) {
      return List.of();
    }
    String body;
    try {
      body =
          client
              .get()
              .uri(
                  uri ->
                      uri.path("/" + SERIES_KEY)
                          .queryParam("startPeriod", from)
                          .queryParam("endPeriod", to)
                          .queryParam("format", "csvdata")
                          .queryParam("detail", "dataonly")
                          .build())
              .retrieve()
              .body(String.class);
    } catch (HttpClientErrorException e) {
      // The data API answers a query that matches no observation with 404 "No results found".
      if (e.getStatusCode().isSameCodeAs(HttpStatus.NOT_FOUND)) {
        return List.of();
      }
      throw new FxRateProviderException(
          "ECB rejected the request for " + from + " to " + to + ": " + e.getStatusCode(), e);
    } catch (RestClientException e) {
      throw new FxRateProviderException(
          "ECB rates for " + from + " to " + to + " could not be fetched", e);
    }
    return parse(body);
  }

  static List<ProvidedFxRate> parse(String body) {
    if (body == null || body.isBlank()) {
      return List.of();
    }
    List<String> lines = body.lines().filter(line -> !line.isBlank()).toList();
    List<String> header = Arrays.asList(lines.get(0).split(",", -1));
    int currencyColumn = column(header, "CURRENCY");
    int denominatorColumn = column(header, "CURRENCY_DENOM");
    int dateColumn = column(header, "TIME_PERIOD");
    int valueColumn = column(header, "OBS_VALUE");
    int lastColumn =
        Math.max(Math.max(currencyColumn, denominatorColumn), Math.max(dateColumn, valueColumn));

    List<ProvidedFxRate> rates = new ArrayList<>(lines.size());
    int skipped = 0;
    for (String line : lines.subList(1, lines.size())) {
      String[] fields = line.split(",", -1);
      if (fields.length <= lastColumn) {
        throw new FxRateProviderException("ECB answered with an unreadable row: " + quote(line));
      }
      ProvidedFxRate rate =
          toRate(
              fields[denominatorColumn],
              fields[currencyColumn],
              fields[dateColumn],
              fields[valueColumn]);
      if (rate == null) {
        skipped++;
      } else {
        rates.add(rate);
      }
    }
    if (skipped > 0) {
      LOG.info("Skipped {} ECB observation(s) without a usable rate", skipped);
    }
    return rates;
  }

  // Null for an observation that cannot be stored: no value (NaN marks a day a discontinued series
  // did not publish), a code java.util.Currency does not know, or a rate fx_rate cannot hold.
  private static ProvidedFxRate toRate(
      String denominator, String currency, String date, String value) {
    if (!EURO.equals(denominator) || !isIsoCurrency(currency)) {
      return null;
    }
    BigDecimal rate;
    try {
      rate = new BigDecimal(value);
    } catch (NumberFormatException e) {
      return null;
    }
    if (rate.signum() <= 0 || rate.compareTo(MAX_RATE) >= 0) {
      return null;
    }
    try {
      return new ProvidedFxRate(EURO, currency, LocalDate.parse(date), rate);
    } catch (DateTimeParseException e) {
      throw new FxRateProviderException("ECB answered with an unreadable date: " + quote(date), e);
    }
  }

  private static boolean isIsoCurrency(String code) {
    try {
      Currency.getInstance(code);
      return true;
    } catch (IllegalArgumentException e) {
      return false;
    }
  }

  private static int column(List<String> header, String name) {
    int index = header.indexOf(name);
    if (index < 0) {
      throw new FxRateProviderException(
          "ECB answer has no " + name + " column: " + quote(String.join(",", header)));
    }
    return index;
  }

  // Provider text in a message ends up in the log: control characters (a forged line break) are
  // replaced and the quote is cut short.
  static String quote(String text) {
    String printable = text.replaceAll("\\p{Cntrl}", "?");
    return printable.length() <= MAX_QUOTED_CHARS
        ? printable
        : printable.substring(0, MAX_QUOTED_CHARS) + "...";
  }

  private static RestClient buildRestClient(
      URI baseUrl, HttpClient httpClient, Duration readTimeout) {
    JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
    factory.setReadTimeout(readTimeout);
    return RestClient.builder().baseUrl(baseUrl.toString()).requestFactory(factory).build();
  }
}
