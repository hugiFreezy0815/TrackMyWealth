package com.trackmywealth.backend.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.trackmywealth.backend.dto.CurrencyConversionResult;
import com.trackmywealth.backend.dto.FxRateLookupResult;
import com.trackmywealth.backend.entity.FxRate;
import com.trackmywealth.backend.repository.FxRateRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.Period;
import java.util.stream.Stream;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
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
 *
 * <p>US-06-02's Definition of Done: {@link
 * #directPairIsUsedEvenWhenChainingThroughTheContainerCurrencyWouldGiveADifferentAnswer} is the
 * three-currency (USD account, EUR container, CHF user) scenario the story's own DoD names,
 * re-scoped to the primitive level per the standing project decision to defer real institution
 * summary/consolidated reporting integration to EPIC-18/19 (neither exists yet): it proves {@link
 * FxRateService#convert} produces the direct-pair figure, not what naively chaining through the
 * container currency would have produced, by seeding rates where the two disagree. The remaining
 * new tests cover the chain-fallback path (AC #2) and its own edge cases.
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

  // Review of PR #225, finding 4: a non-positive stale-after would mark a weekend's rate stale.
  @Test
  void aNonPositiveStaleAfterIsRejectedAtStartup() {
    for (Period invalid : new Period[] {Period.ZERO, Period.ofDays(-5)}) {
      assertThatThrownBy(() -> new FxRateService(fxRateRepository, null, null, invalid))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessage("app.fx.stale-after must be a positive period.");
    }
  }

  // V54: a cross rate the import derived is a stored pair, but says it went through the euro.
  @Test
  void aStoredDerivedCrossRateIsUsedAsStoredAndReportedAsViaTheEuro() {
    LocalDate date = LocalDate.of(2026, 9, 15);
    FxRate derived = new FxRate();
    derived.setBaseCurrency("USD");
    derived.setQuoteCurrency("CHF");
    derived.setRateDate(date);
    derived.setRate(new BigDecimal("0.7956000000"));
    derived.setSource(SOURCE);
    derived.setDerived(true);
    fxRateRepository.save(derived);

    CurrencyConversionResult result = fxRateService.getConversionRate("USD", "CHF", date, SOURCE);

    assertThat(result.rate()).isEqualByComparingTo("0.7956");
    assertThat(result.direct()).isFalse();
    assertThat(result.intermediateCurrency()).isEqualTo("EUR");
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
  void longerGapIsStillCarriedForwardFromTheLastStoredValueAndMarkedStale() {
    // NFR-CON-003's "provider unavailable" case: the last stored value is served, carried forward
    // and - being older than app.fx.stale-after (P5D) - marked stale.
    LocalDate lastKnown = LocalDate.of(2026, 8, 1);
    LocalDate today = LocalDate.of(2026, 9, 15);
    seedRate("USD", "CHF", lastKnown, "0.9050000000", SOURCE);

    FxRateLookupResult result = fxRateService.getRate("USD", "CHF", today, SOURCE);

    assertThat(result.rateDate()).isEqualTo(lastKnown);
    assertThat(result.carriedForward()).isTrue();
    assertThat(result.stale()).isTrue();
  }

  @Test
  void aWeekendOrHolidayGapIsCarriedForwardButNotStale() {
    // Easter: Thursday's rate serves Good Friday to Easter Monday - four days, within P5D.
    LocalDate maundyThursday = LocalDate.of(2026, 4, 2);
    LocalDate easterMonday = LocalDate.of(2026, 4, 6);
    seedRate("USD", "CHF", maundyThursday, "0.9050000000", SOURCE);

    CurrencyConversionResult result =
        fxRateService.getConversionRate("USD", "CHF", easterMonday, SOURCE);

    assertThat(result.carriedForward()).isTrue();
    assertThat(result.stale()).isFalse();
  }

  @Test
  void aRateExactlyStaleAfterOldIsNotYetStaleOneDayMoreIs() {
    LocalDate rateDate = LocalDate.of(2026, 9, 10);
    seedRate("USD", "CHF", rateDate, "0.9050000000", SOURCE);

    assertThat(fxRateService.getRate("USD", "CHF", rateDate.plusDays(5), SOURCE).stale()).isFalse();
    assertThat(fxRateService.getRate("USD", "CHF", rateDate.plusDays(6), SOURCE).stale()).isTrue();
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

  @ParameterizedTest
  @MethodSource("invalidArguments")
  void invalidArgumentsAreRejectedAsBadRequestNotAnAlwaysFalseQuery(
      String base, String quote, LocalDate date, String source) {
    assertThatBadRequest(() -> fxRateService.getRate(base, quote, date, source));
  }

  private static Stream<Arguments> invalidArguments() {
    LocalDate today = LocalDate.now();
    return Stream.of(
        Arguments.of("USD", "NOTACODE", today, SOURCE), // malformed currency code
        Arguments.of(null, "CHF", today, SOURCE), // null currency
        Arguments.of("USD", "CHF", null, SOURCE), // null date
        Arguments.of("USD", "CHF", today, " ")); // blank source
  }

  private void assertThatBadRequest(ThrowingCallable call) {
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

  @Test
  void directPairIsUsedEvenWhenChainingThroughTheContainerCurrencyWouldGiveADifferentAnswer() {
    // US-06-02/FR-CUR-010's DoD scenario: a USD account inside a EUR container, reported in CHF.
    // Deliberately seeded so the direct USD/CHF rate and the naive USD->EUR->CHF chain disagree
    // (0.8850 vs 0.92 * 0.96 = 0.8832) - real-world triangulation is never perfectly consistent,
    // which is exactly why FR-CUR-010 exists. convert() must return the direct-pair figure.
    LocalDate today = LocalDate.of(2026, 9, 15);
    seedRate("USD", "CHF", today, "0.8850000000", SOURCE);
    seedRate("USD", "EUR", today, "0.9200000000", SOURCE);
    seedRate("EUR", "CHF", today, "0.9600000000", SOURCE);
    BigDecimal usdAmount = new BigDecimal("1000.00");

    BigDecimal consolidatedViewAmount =
        fxRateService.convert(usdAmount, "USD", "CHF", today, SOURCE);
    CurrencyConversionResult conversion =
        fxRateService.getConversionRate("USD", "CHF", today, SOURCE);

    assertThat(consolidatedViewAmount).isEqualByComparingTo("885.0000");
    assertThat(conversion.direct()).isTrue();
    assertThat(conversion.intermediateCurrency()).isNull();
    assertThat(conversion.carriedForward()).isFalse();
    // The naive chain a container-currency-intermediated implementation would have produced -
    // proving convert() did NOT take this path, not just that it returned some value.
    BigDecimal naiveChainedAmount =
        usdAmount.multiply(new BigDecimal("0.9200000000")).multiply(new BigDecimal("0.9600000000"));
    assertThat(consolidatedViewAmount).isNotEqualByComparingTo(naiveChainedAmount);
  }

  @Test
  void noDirectPairChainsThroughTheDocumentedIntermediateCurrency() {
    // AC #2: no direct GBP/CHF rate exists, but both legs to EUR (this codebase's documented
    // fallback intermediate) do - chosen so the chained rate is a clean round number.
    LocalDate today = LocalDate.of(2026, 9, 15);
    seedRate("GBP", "EUR", today, "2.0000000000", SOURCE);
    seedRate("EUR", "CHF", today, "0.5000000000", SOURCE);

    CurrencyConversionResult conversion =
        fxRateService.getConversionRate("GBP", "CHF", today, SOURCE);

    assertThat(conversion.direct()).isFalse();
    assertThat(conversion.intermediateCurrency()).isEqualTo("EUR");
    assertThat(conversion.rate()).isEqualByComparingTo("1.0000000000");
    assertThat(conversion.carriedForward()).isFalse();
    assertThat(fxRateService.convert(new BigDecimal("100"), "GBP", "CHF", today, SOURCE))
        .isEqualByComparingTo("100.0000");
  }

  @Test
  void reverseStoredPairIsInvertedAndCountsAsDirect() {
    // #223: the ECB publishes EUR/CHF only; a CHF amount in EUR is the same fact read backwards.
    LocalDate today = LocalDate.of(2026, 9, 15);
    seedRate("EUR", "CHF", today, "0.8000000000", SOURCE);

    CurrencyConversionResult conversion =
        fxRateService.getConversionRate("CHF", "EUR", today, SOURCE);

    assertThat(conversion.rate()).isEqualByComparingTo("1.25");
    assertThat(conversion.direct()).isTrue();
    assertThat(conversion.intermediateCurrency()).isNull();
    assertThat(fxRateService.convert(new BigDecimal("80.00"), "CHF", "EUR", today, SOURCE))
        .isEqualByComparingTo("100.0000");
  }

  @Test
  void pairOfTwoEcbPublishedCurrenciesIsCrossedThroughTheEuro() {
    // #223: with nothing but the ECB's EUR-based rates stored, USD/CHF = USD/EUR * EUR/CHF, the
    // first leg itself inverted from EUR/USD: (1 / 1.25) * 0.9 = 0.72.
    LocalDate today = LocalDate.of(2026, 9, 15);
    seedRate("EUR", "USD", today, "1.2500000000", SOURCE);
    seedRate("EUR", "CHF", today, "0.9000000000", SOURCE);

    CurrencyConversionResult usdToChf =
        fxRateService.getConversionRate("USD", "CHF", today, SOURCE);
    CurrencyConversionResult chfToUsd =
        fxRateService.getConversionRate("CHF", "USD", today, SOURCE);

    assertThat(usdToChf.rate()).isEqualByComparingTo("0.72");
    assertThat(usdToChf.direct()).isFalse();
    assertThat(usdToChf.intermediateCurrency()).isEqualTo("EUR");
    // 0.9 inverted is 1.111...; times 1.25 = 1.3888..., rounded once at the end.
    assertThat(chfToUsd.rate()).isEqualByComparingTo("1.3888888889");
  }

  @Test
  void derivedRateIsRoundedOnceToTheStoredRatePrecision() {
    LocalDate today = LocalDate.of(2026, 9, 15);
    seedRate("EUR", "CHF", today, "3", SOURCE);

    BigDecimal rate = fxRateService.getConversionRate("CHF", "EUR", today, SOURCE).rate();

    assertThat(rate).isEqualTo(new BigDecimal("0.3333333333"));
  }

  @Test
  void storedPairWinsOverTheInvertedReversePair() {
    // Independently sourced rates are never exact reciprocals; the pair as asked for is used.
    LocalDate today = LocalDate.of(2026, 9, 15);
    seedRate("CHF", "EUR", today, "1.1000000000", SOURCE);
    seedRate("EUR", "CHF", today, "0.9500000000", SOURCE);

    assertThat(fxRateService.getConversionRate("CHF", "EUR", today, SOURCE).rate())
        .isEqualByComparingTo("1.1");
  }

  @Test
  void chainedConversionIsMarkedCarriedForwardWhenEitherLegIs() {
    LocalDate monday = LocalDate.of(2026, 9, 14);
    LocalDate tuesday = monday.plusDays(1);
    seedRate("EUR", "GBP", monday, "0.5000000000", SOURCE); // one day old
    seedRate("EUR", "CHF", tuesday, "0.9000000000", SOURCE); // exact

    CurrencyConversionResult conversion =
        fxRateService.getConversionRate("GBP", "CHF", tuesday, SOURCE);

    assertThat(conversion.carriedForward()).isTrue();
    assertThat(conversion.stale()).isFalse();
  }

  @Test
  void chainedConversionIsStaleWhenEitherLegIs() {
    LocalDate today = LocalDate.of(2026, 9, 15);
    seedRate("EUR", "GBP", today.minusDays(10), "0.5000000000", SOURCE);
    seedRate("EUR", "CHF", today, "0.9000000000", SOURCE);

    assertThat(fxRateService.getConversionRate("GBP", "CHF", today, SOURCE).stale()).isTrue();
  }

  @Test
  void chainFallbackFailsClosedWhenOnlyOneLegExists() {
    // Refuse rather than silently compute with a missing rate defaulted to 1.0 (PR-011) - even
    // though one leg (GBP/EUR) is available, CHF against EUR is not, so no complete chain exists.
    LocalDate today = LocalDate.of(2026, 9, 15);
    seedRate("GBP", "EUR", today, "2.0000000000", SOURCE);

    assertThatThrownBy(() -> fxRateService.getConversionRate("GBP", "CHF", today, SOURCE))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(
            e ->
                assertThat(((ResponseStatusException) e).getStatusCode())
                    .isEqualTo(HttpStatus.NOT_FOUND));
  }

  @Test
  void missingDirectPairInvolvingTheIntermediateItselfCannotBeRecoveredByChaining() {
    // EUR is this codebase's own fallback intermediate (see FxRateService's class Javadoc) - a
    // missing EUR/JPY pair can't be recovered by "chaining via EUR" since EUR is already one side
    // of the request; there is no third currency to chain through.
    LocalDate today = LocalDate.of(2026, 9, 15);
    seedRate("USD", "JPY", today, "150.0000000000", SOURCE); // present, but irrelevant here

    assertThatThrownBy(() -> fxRateService.getConversionRate("EUR", "JPY", today, SOURCE))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(
            e ->
                assertThat(((ResponseStatusException) e).getStatusCode())
                    .isEqualTo(HttpStatus.NOT_FOUND));
  }

  @Test
  void convertRoundsToFourDecimalPlacesHalfUpPerTheDocumentedMoneyPolicy() {
    LocalDate today = LocalDate.of(2026, 9, 15);
    seedRate("USD", "CHF", today, "0.3333333333", SOURCE);

    BigDecimal converted = fxRateService.convert(BigDecimal.ONE, "USD", "CHF", today, SOURCE);

    assertThat(converted).isEqualByComparingTo("0.3333");
    assertThat(converted.scale()).isEqualTo(4);
  }

  @Test
  void applyRateGivesTheSameResultAsConvertWithoutReResolvingTheRate() {
    // InstitutionService's own case: a caller that already called getConversionRate for its
    // metadata (carriedForward/direct/intermediateCurrency) applies the same resolution's rate
    // via applyRate instead of calling convert() a second time - must agree exactly with what
    // convert() itself would have produced from scratch.
    LocalDate today = LocalDate.of(2026, 9, 15);
    seedRate("USD", "CHF", today, "0.3333333333", SOURCE);

    CurrencyConversionResult conversion =
        fxRateService.getConversionRate("USD", "CHF", today, SOURCE);
    BigDecimal applied = fxRateService.applyRate(BigDecimal.ONE, conversion);

    assertThat(applied).isEqualByComparingTo("0.3333");
    assertThat(applied.scale()).isEqualTo(4);
    assertThat(applied)
        .isEqualByComparingTo(fxRateService.convert(BigDecimal.ONE, "USD", "CHF", today, SOURCE));
  }

  @Test
  void sameCurrencyConversionIsAlwaysRateOneWithNoRateLookupNeeded() {
    // Code review finding on this PR: with no short-circuit, a same-currency pair with no direct
    // row stored would fall into the chain-fallback logic below and either 404 or silently
    // multiply mismatched reciprocal rates - neither is correct for what is definitionally a
    // rate of exactly 1.0. No rate seeded at all here, proving no lookup is even attempted.
    LocalDate today = LocalDate.of(2026, 9, 15);

    CurrencyConversionResult conversion =
        fxRateService.getConversionRate("EUR", "EUR", today, SOURCE);

    assertThat(conversion.rate()).isEqualByComparingTo("1");
    assertThat(conversion.direct()).isTrue();
    assertThat(conversion.intermediateCurrency()).isNull();
    assertThat(conversion.carriedForward()).isFalse();
    assertThat(fxRateService.convert(new BigDecimal("250.00"), "CHF", "CHF", today, SOURCE))
        .isEqualByComparingTo("250.0000");
  }

  @Test
  void intermediateToItselfSucceedsRatherThanFailingAsIfItCouldNotChainThroughItself() {
    // The specific case a review flagged: without the same-currency short-circuit, the fallback
    // intermediate (EUR) to itself hit the "can't chain through yourself" guard and 404'd instead
    // of trivially succeeding.
    LocalDate today = LocalDate.of(2026, 9, 15);

    assertThat(fxRateService.getConversionRate("EUR", "EUR", today, SOURCE).rate())
        .isEqualByComparingTo("1");
  }

  @Test
  void sameCurrencyShortCircuitWinsEvenWhenMismatchedReciprocalRatesAreStored() {
    // Proves the fix, not just its absence of failure: with EUR/USD and USD/EUR stored as
    // non-reciprocal values (realistic - independently sourced, not derived from each other), the
    // old code computed 0.9200000000 * 1.0900000000 = 1.0028000000 as "the EUR/EUR rate" instead
    // of 1.0. This must return exactly 1, not that chained product.
    LocalDate today = LocalDate.of(2026, 9, 15);
    seedRate("EUR", "USD", today, "0.9200000000", SOURCE);
    seedRate("USD", "EUR", today, "1.0900000000", SOURCE);

    assertThat(fxRateService.getConversionRate("EUR", "EUR", today, SOURCE).rate())
        .isEqualByComparingTo("1");
  }
}
