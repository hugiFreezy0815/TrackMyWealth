package com.trackmywealth.backend.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withResourceNotFound;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/**
 * #223: the ECB data API's CSV answer, read without the network - the request it sends, the rows it
 * keeps and skips, and how each kind of failure surfaces.
 */
class EcbFxRateProviderTest {

  private static final String BASE_URL = "https://ecb.test/service/data/EXR";
  private static final String HEADER =
      "KEY,FREQ,CURRENCY,CURRENCY_DENOM,EXR_TYPE,EXR_SUFFIX,TIME_PERIOD,OBS_VALUE\n";
  private static final LocalDate FROM = LocalDate.of(2026, 9, 28);
  private static final LocalDate TO = LocalDate.of(2026, 9, 29);
  private static final String EXPECTED_URL =
      BASE_URL
          + "/D..EUR.SP00.A?startPeriod=2026-09-28&endPeriod=2026-09-29"
          + "&format=csvdata&detail=dataonly";

  private MockRestServiceServer server;
  private EcbFxRateProvider provider;

  @BeforeEach
  void setUp() {
    RestClient.Builder builder = RestClient.builder().baseUrl(BASE_URL);
    server = MockRestServiceServer.bindTo(builder).build();
    provider = new EcbFxRateProvider(builder.build());
  }

  @Test
  void readsEveryPublishedRateAsEuroBasedPairs() {
    expectCsv(
        HEADER
            + "EXR.D.CHF.EUR.SP00.A,D,CHF,EUR,SP00,A,2026-09-28,0.9312\n"
            + "EXR.D.USD.EUR.SP00.A,D,USD,EUR,SP00,A,2026-09-28,1.1301\n"
            + "EXR.D.CHF.EUR.SP00.A,D,CHF,EUR,SP00,A,2026-09-29,0.9298\n");

    List<ProvidedFxRate> rates = provider.fetch(FROM, TO);

    assertThat(rates)
        .containsExactly(
            new ProvidedFxRate("EUR", "CHF", FROM, new BigDecimal("0.9312")),
            new ProvidedFxRate("EUR", "USD", FROM, new BigDecimal("1.1301")),
            new ProvidedFxRate("EUR", "CHF", TO, new BigDecimal("0.9298")));
    assertThat(provider.source()).isEqualTo("ECB");
    server.verify();
  }

  @Test
  void findsColumnsByNameWhateverTheirOrder() {
    expectCsv("OBS_VALUE,TIME_PERIOD,CURRENCY_DENOM,CURRENCY\n" + "1.6255,2026-09-28,EUR,AUD\n");

    assertThat(provider.fetch(FROM, TO))
        .containsExactly(new ProvidedFxRate("EUR", "AUD", FROM, new BigDecimal("1.6255")));
  }

  @Test
  void skipsObservationsThatCannotBeStored() {
    expectCsv(
        HEADER
            // A discontinued series publishes NaN on the days it no longer quotes.
            + "EXR.D.RUB.EUR.SP00.A,D,RUB,EUR,SP00,A,2026-09-28,NaN\n"
            + "EXR.D.CHF.EUR.SP00.A,D,CHF,EUR,SP00,A,2026-09-28,\n"
            // Not an ISO 4217 code java.util.Currency knows.
            + "EXR.D.XYZ.EUR.SP00.A,D,XYZ,EUR,SP00,A,2026-09-28,2.5\n"
            // Quoted against another currency than the euro.
            + "EXR.D.CHF.USD.SP00.A,D,CHF,USD,SP00,A,2026-09-28,0.82\n"
            // Zero, negative, or too large for fx_rate.rate NUMERIC(20,10).
            + "EXR.D.GBP.EUR.SP00.A,D,GBP,EUR,SP00,A,2026-09-28,0\n"
            + "EXR.D.SEK.EUR.SP00.A,D,SEK,EUR,SP00,A,2026-09-28,-1\n"
            + "EXR.D.IDR.EUR.SP00.A,D,IDR,EUR,SP00,A,2026-09-28,10000000000\n"
            + "EXR.D.USD.EUR.SP00.A,D,USD,EUR,SP00,A,2026-09-28,1.1301\n");

    assertThat(provider.fetch(FROM, TO))
        .containsExactly(new ProvidedFxRate("EUR", "USD", FROM, new BigDecimal("1.1301")));
  }

  @Test
  void aRangeWithNothingPublishedIsEmptyWhetherAnsweredEmptyOrNotFound() {
    // A weekend: the API answers 200 with an empty body, or 404 "No results found".
    expectCsv("");
    assertThat(provider.fetch(FROM, TO)).isEmpty();

    server.reset();
    server.expect(requestTo(EXPECTED_URL)).andRespond(withResourceNotFound());
    assertThat(provider.fetch(FROM, TO)).isEmpty();

    server.reset();
    expectCsv(HEADER);
    assertThat(provider.fetch(FROM, TO)).isEmpty();
  }

  @Test
  void aRangeEndingBeforeItStartsMakesNoRequest() {
    assertThat(provider.fetch(TO, FROM)).isEmpty();
    server.verify();
  }

  @Test
  void aServerErrorIsAProviderFailure() {
    server.expect(requestTo(EXPECTED_URL)).andRespond(withServerError());

    assertThatThrownBy(() -> provider.fetch(FROM, TO)).isInstanceOf(FxRateProviderException.class);
  }

  @Test
  void anUnreachableProviderIsAProviderFailure() {
    server
        .expect(requestTo(EXPECTED_URL))
        .andRespond(withException(new IOException("connection refused")));

    assertThatThrownBy(() -> provider.fetch(FROM, TO))
        .isInstanceOf(FxRateProviderException.class)
        .hasMessageContaining("could not be fetched");
  }

  @Test
  void anAnswerWithoutTheExpectedColumnsIsAProviderFailure() {
    expectCsv("<html>maintenance</html>\n");

    assertThatThrownBy(() -> provider.fetch(FROM, TO))
        .isInstanceOf(FxRateProviderException.class)
        .hasMessageContaining("no CURRENCY column");
  }

  @Test
  void aTruncatedRowOrUnreadableDateIsAProviderFailure() {
    expectCsv(HEADER + "EXR.D.CHF.EUR.SP00.A,D,CHF\n");
    assertThatThrownBy(() -> provider.fetch(FROM, TO)).isInstanceOf(FxRateProviderException.class);

    server.reset();
    expectCsv(HEADER + "EXR.D.CHF.EUR.SP00.A,D,CHF,EUR,SP00,A,2026-W39,0.93\n");
    assertThatThrownBy(() -> provider.fetch(FROM, TO))
        .isInstanceOf(FxRateProviderException.class)
        .hasMessageContaining("unreadable date");
  }

  private void expectCsv(String body) {
    server
        .expect(requestTo(EXPECTED_URL))
        .andExpect(method(HttpMethod.GET))
        .andRespond(withSuccess(body, MediaType.parseMediaType("text/csv")));
  }
}
