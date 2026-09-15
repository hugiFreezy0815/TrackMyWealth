package com.trackmywealth.backend.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.trackmywealth.backend.dto.FxRateLookupResult;
import com.trackmywealth.backend.entity.FxRate;
import com.trackmywealth.backend.repository.FxRateRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * US-06-01's Definition of Done: same-day exact rate, weekend carry-forward, and the staleness flag
 * - plus the AC#2 no-duplicate constraint and the AC "no rate at all" refusal (PR-011). Rows are
 * seeded directly via {@link FxRateRepository}, matching this story's own Dependencies note that
 * the storage/read contract is testable with manually inserted rates ahead of EPIC 30's scheduled
 * fetch job.
 */
@Testcontainers
@SpringBootTest
class FxRateServiceTest {

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

  private static final String SOURCE = "MANUAL";

  @Autowired FxRateService fxRateService;
  @Autowired FxRateRepository fxRateRepository;

  @AfterEach
  void cleanDatabase() {
    fxRateRepository.deleteAll();
  }

  private FxRate seedRate(String base, String quote, LocalDate date, String rate, String source) {
    FxRate fxRate = new FxRate();
    fxRate.setBaseCurrency(base);
    fxRate.setQuoteCurrency(quote);
    fxRate.setRateDate(date);
    fxRate.setRate(new BigDecimal(rate));
    fxRate.setSource(source);
    return fxRateRepository.save(fxRate);
  }

  @Test
  void exactSameDayRateIsReturnedUnmarked() {
    LocalDate today = LocalDate.of(2026, 9, 15); // a Tuesday
    seedRate("USD", "CHF", today, "0.9123456789", SOURCE);

    FxRateLookupResult result = fxRateService.getRate("USD", "CHF", today, SOURCE);

    assertThat(result.rate()).isEqualByComparingTo("0.9123456789");
    assertThat(result.rateDate()).isEqualTo(today);
    assertThat(result.carriedForward()).isFalse();
  }

  @Test
  void weekendGapCarriesForwardTheLastAvailablePriorRate() {
    LocalDate friday = LocalDate.of(2026, 9, 11);
    LocalDate saturday = friday.plusDays(1);
    seedRate("USD", "CHF", friday, "0.9100000000", SOURCE);

    FxRateLookupResult result = fxRateService.getRate("USD", "CHF", saturday, SOURCE);

    assertThat(result.rate()).isEqualByComparingTo("0.9100000000");
    assertThat(result.requestedDate()).isEqualTo(saturday);
    assertThat(result.rateDate()).isEqualTo(friday);
    assertThat(result.carriedForward()).isTrue();
  }

  @Test
  void longerGapIsStillCarriedForwardFromTheLastStoredValue() {
    // Simulates NFR-CON-003's "provider unavailable" case: the last stored value is served and
    // marked, however long ago it was actually retrieved - the same mechanism as the weekend
    // case above, since no scheduled job exists yet in this sprint to distinguish "weekend" from
    // "provider outage" (see the class Javadoc and US-06-01's own scoping note).
    LocalDate lastKnown = LocalDate.of(2026, 8, 1);
    LocalDate today = LocalDate.of(2026, 9, 15);
    seedRate("USD", "CHF", lastKnown, "0.9050000000", SOURCE);

    FxRateLookupResult result = fxRateService.getRate("USD", "CHF", today, SOURCE);

    assertThat(result.rateDate()).isEqualTo(lastKnown);
    assertThat(result.carriedForward()).isTrue();
  }

  @Test
  void mostRecentPriorRateIsUsedNotTheOldestOne() {
    LocalDate today = LocalDate.of(2026, 9, 15);
    seedRate("USD", "CHF", today.minusDays(5), "0.9000000000", SOURCE);
    seedRate("USD", "CHF", today.minusDays(1), "0.9200000000", SOURCE);

    FxRateLookupResult result = fxRateService.getRate("USD", "CHF", today, SOURCE);

    assertThat(result.rateDate()).isEqualTo(today.minusDays(1));
    assertThat(result.rate()).isEqualByComparingTo("0.9200000000");
  }

  @Test
  void noStoredRateOnOrBeforeTheDateIsRefusedNotDefaultedToOne() {
    LocalDate today = LocalDate.of(2026, 9, 15);

    assertThatThrownBy(() -> fxRateService.getRate("USD", "CHF", today, SOURCE))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(
            e ->
                assertThat(((ResponseStatusException) e).getStatusCode())
                    .isEqualTo(HttpStatus.NOT_FOUND));
  }

  @Test
  void lowercaseCurrencyCodeIsRejectedAsBadRequestNotSilentlyNoMatch() {
    LocalDate today = LocalDate.of(2026, 9, 15);
    seedRate("USD", "CHF", today, "0.9100000000", SOURCE);

    assertThatBadRequest(() -> fxRateService.getRate("usd", "CHF", today, SOURCE));
  }

  @Test
  void malformedCurrencyCodeIsRejectedAsBadRequest() {
    assertThatBadRequest(() -> fxRateService.getRate("USD", "NOTACODE", LocalDate.now(), SOURCE));
  }

  @Test
  void nullCurrencyIsRejectedAsBadRequest() {
    assertThatBadRequest(() -> fxRateService.getRate(null, "CHF", LocalDate.now(), SOURCE));
  }

  @Test
  void nullDateIsRejectedAsBadRequestNotAnAlwaysFalseQuery() {
    assertThatBadRequest(() -> fxRateService.getRate("USD", "CHF", null, SOURCE));
  }

  @Test
  void blankSourceIsRejectedAsBadRequest() {
    assertThatBadRequest(() -> fxRateService.getRate("USD", "CHF", LocalDate.now(), " "));
  }

  private void assertThatBadRequest(org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
    assertThatThrownBy(call)
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(
            e ->
                assertThat(((ResponseStatusException) e).getStatusCode())
                    .isEqualTo(HttpStatus.BAD_REQUEST));
  }

  @Test
  void duplicatePairDateSourceIsRejectedByTheDatabase() {
    LocalDate today = LocalDate.of(2026, 9, 15);
    seedRate("USD", "CHF", today, "0.9100000000", SOURCE);

    assertThatThrownBy(
            () -> {
              seedRate("USD", "CHF", today, "0.9200000000", SOURCE);
              fxRateRepository.flush();
            })
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  void differentSourcesForTheSamePairDateDoNotCollideAndAreQueriedIndependently() {
    LocalDate today = LocalDate.of(2026, 9, 15);
    seedRate("USD", "CHF", today, "0.9100000000", "PROVIDER_A");
    seedRate("USD", "CHF", today, "0.9150000000", "PROVIDER_B");

    assertThat(fxRateService.getRate("USD", "CHF", today, "PROVIDER_A").rate())
        .isEqualByComparingTo("0.9100000000");
    assertThat(fxRateService.getRate("USD", "CHF", today, "PROVIDER_B").rate())
        .isEqualByComparingTo("0.9150000000");
  }
}
