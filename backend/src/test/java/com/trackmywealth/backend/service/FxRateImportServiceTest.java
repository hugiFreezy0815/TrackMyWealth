package com.trackmywealth.backend.service;

import static java.math.RoundingMode.HALF_UP;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.trackmywealth.backend.client.EcbFxRateProvider;
import com.trackmywealth.backend.client.FxRateProvider;
import com.trackmywealth.backend.client.FxRateProviderDefinition;
import com.trackmywealth.backend.client.FxRateProviderException;
import com.trackmywealth.backend.client.ProvidedFxRate;
import com.trackmywealth.backend.config.FxRateImportJobConfig;
import com.trackmywealth.backend.dto.CurrencyConversionResult;
import com.trackmywealth.backend.entity.FxRate;
import com.trackmywealth.backend.job.FxRateImportJob;
import com.trackmywealth.backend.repository.FxRateBatchRepository;
import com.trackmywealth.backend.repository.FxRateRepository;
import com.trackmywealth.backend.testsupport.MutableClock;
import com.trackmywealth.backend.testsupport.TestClockConfig;
import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.quartz.CronTrigger;
import org.quartz.JobDataMap;
import org.quartz.JobExecutionContext;
import org.quartz.JobKey;
import org.quartz.Scheduler;
import org.quartz.SchedulerContext;
import org.quartz.TriggerKey;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * US-06-04 (#223) Definition of Done, against a real PostgreSQL and a stubbed provider that
 * publishes EUR/CHF and EUR/USD on every weekday: the scheduled import, the first run's full
 * history, the history backfill after an older booking, fetch-on-missing for a transaction being
 * created (and no fetch for any other read), the weekend carry-forward that fetches nothing, the
 * provider-down fallback to the last stored rate, marked stale, and the cross rates between the
 * currencies in use stored as master data (V54).
 *
 * <p>"Today" is Wednesday 2026-09-16 unless a test moves the clock. Each test asks for dates of its
 * own: the service remembers on-demand attempts and an exhausted history start in memory.
 */
@Testcontainers
@SpringBootTest(
    properties = {
      "app.fx.import.enabled=true",
      // The jobs are registered (asserted below) but never fire on their own during a test.
      "spring.quartz.auto-startup=false"
    })
@Import({TestClockConfig.class, FxRateImportServiceTest.StubProviderConfig.class})
@ExtendWith(OutputCaptureExtension.class)
class FxRateImportServiceTest {

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

  @Autowired FxRateImportService importService;
  @Autowired FxRateService fxRateService;
  @Autowired FxRateRepository fxRateRepository;
  @Autowired FxRateBatchRepository fxRateBatchRepository;
  @Autowired StubFxRateProvider provider;
  @Autowired MutableClock clock;
  @Autowired JdbcTemplate jdbcTemplate;
  @Autowired Scheduler scheduler;
  @Autowired TransferRecheckService transferRecheckService;

  @BeforeEach
  void setUp() {
    setToday(TODAY);
    provider.reset();
  }

  @AfterEach
  void cleanDatabase() {
    fxRateRepository.deleteAll();
    jdbcTemplate.update("DELETE FROM fx_rate_currency_in_use");
    setEarliestBooking(null);
  }

  @Test
  void dailyImportStoresEveryPublishedRateOnceAndResumesAfterTheLastStoredDay() {
    // Nothing booked, nothing stored: the first run covers the last week, so today's conversions
    // can carry forward over a weekend straight away. Weekdays 9th-11th and 14th-16th.
    assertThat(importService.importLatest()).isEqualTo(12);
    assertThat(provider.calls).containsExactly(range("2026-09-09", "2026-09-16"));

    // Same day again: nothing after the latest stored day, so no call and no duplicate.
    assertThat(importService.importLatest()).isZero();
    assertThat(provider.calls).hasSize(1);
    assertThat(fxRateRepository.count()).isEqualTo(12);

    setToday(TODAY.plusDays(1));
    assertThat(importService.importLatest()).isEqualTo(2);
    assertThat(provider.calls.get(1)).isEqualTo(range("2026-09-17", "2026-09-17"));

    CurrencyConversionResult usdToChf =
        fxRateService.getConversionRate("USD", "CHF", TODAY.plusDays(1), "ECB");
    assertThat(usdToChf.carriedForward()).isFalse();
    assertThat(usdToChf.intermediateCurrency()).isEqualTo("EUR");
  }

  @Test
  void aRateAlreadyStoredForTheDayIsKeptNeitherDuplicatedNorReplaced() {
    importService.importLatest();
    BigDecimal stored = storedRate("CHF", TODAY);
    FxRate republished = new FxRate();
    republished.setBaseCurrency("EUR");
    republished.setQuoteCurrency("CHF");
    republished.setRateDate(TODAY);
    republished.setRate(stored.add(BigDecimal.ONE));
    republished.setSource("ECB");

    assertThat(fxRateBatchRepository.insertIfAbsent(List.of(republished))).isZero();

    assertThat(storedRate("CHF", TODAY)).isEqualByComparingTo(stored);
    assertThat(fxRateRepository.count()).isEqualTo(12);
  }

  @Test
  void firstRunLoadsTheFullHistoryFromTheFirstBookingInYearSizedChunks() {
    setEarliestBooking(LocalDate.of(2025, 3, 3));

    importService.importLatest();

    // From a week before the first booking (Monday 2025-02-24) to today, in two chunks.
    assertThat(provider.calls)
        .containsExactly(range("2025-02-24", "2026-02-24"), range("2026-02-25", "2026-09-16"));
    assertThat(fxRateRepository.findEarliestRateDate("ECB")).contains(LocalDate.of(2025, 2, 24));
    assertThat(fxRateRepository.findLatestRateDate("ECB")).contains(TODAY);
    assertThat(fxRateRepository.count()).isEqualTo(2L * weekdays(LocalDate.of(2025, 2, 24), TODAY));
  }

  @Test
  void historyBackfillLoadsRatesBackToAnOlderBookingAndThenRestsUntilAnEvenOlderOne() {
    importService.importLatest();
    assertThat(importService.backfillHistory()).isZero(); // nothing booked yet
    provider.calls.clear();

    // An import of old statements booked a transaction on 2026-06-02.
    setEarliestBooking(LocalDate.of(2026, 6, 2));
    assertThat(importService.backfillHistory()).isPositive();

    assertThat(provider.calls).containsExactly(range("2026-05-26", "2026-09-08"));
    assertThat(fxRateRepository.findEarliestRateDate("ECB")).contains(LocalDate.of(2026, 5, 26));

    // Covered now: the hourly check makes no provider call.
    assertThat(importService.backfillHistory()).isZero();
    assertThat(provider.calls).hasSize(1);
  }

  @Test
  void historyTheProviderDoesNotHaveIsAskedForOnceNotEveryHour() {
    // A series that only begins this Monday: nothing exists before it, however often asked.
    provider.seriesStart = LocalDate.of(2026, 9, 14);
    importService.importLatest();
    provider.calls.clear();
    setEarliestBooking(LocalDate.of(2026, 6, 1));

    assertThat(importService.backfillHistory()).isZero();
    assertThat(importService.backfillHistory()).isZero();

    assertThat(provider.calls).containsExactly(range("2026-05-25", "2026-09-13"));
  }

  @Test
  void aTransactionsConversionForADateBeforeEveryStoredRateFetchesStoresAndUsesIt() {
    importService.importLatest();
    provider.calls.clear();
    LocalDate tuesday = LocalDate.of(2026, 3, 10);

    CurrencyConversionResult conversion = fetching("USD", "CHF", tuesday).orElseThrow();

    // Fetched from a week before the date up to the earliest stored rate, so history has no gap.
    assertThat(provider.calls).containsExactly(range("2026-03-03", "2026-09-08"));
    assertThat(conversion.carriedForward()).isFalse();
    assertThat(conversion.rate())
        .isCloseTo(
            provider.rate("CHF", tuesday).divide(provider.rate("USD", tuesday), 10, HALF_UP),
            within(new BigDecimal("1E-10")));
    assertThat(storedRate("CHF", tuesday)).isEqualByComparingTo(provider.rate("CHF", tuesday));
  }

  @Test
  void fetchOnMissingLoadsAYearAtMostAndTheHistoryBackfillClosesTheGapAfterIt() {
    importService.importLatest();
    provider.calls.clear();
    LocalDate oldBooking = LocalDate.of(2024, 1, 10);
    setEarliestBooking(oldBooking);

    fetching("USD", "CHF", oldBooking).orElseThrow();

    assertThat(provider.calls).containsExactly(range("2024-01-03", "2025-01-02"));
    LocalDate inTheGap = LocalDate.of(2025, 6, 3);
    assertThat(fxRateService.getConversionRate("USD", "CHF", inTheGap, "ECB").stale()).isTrue();

    importService.backfillHistory();

    assertThat(provider.calls.subList(1, provider.calls.size()))
        .containsExactly(
            range("2024-01-03", "2025-01-02"),
            range("2025-01-03", "2026-01-03"),
            range("2026-01-04", "2026-09-08"));
    CurrencyConversionResult filled =
        fxRateService.getConversionRate("USD", "CHF", inTheGap, "ECB");
    assertThat(filled.carriedForward()).isFalse();
    assertThat(fxRateRepository.findStartOfLatestRun("ECB", 5)).contains(LocalDate.of(2024, 1, 3));
  }

  @Test
  void weekendConversionCarriesFridayForwardWithoutFetching() {
    importService.importLatest();
    provider.calls.clear();
    LocalDate saturday = LocalDate.of(2026, 9, 12);

    CurrencyConversionResult conversion =
        fxRateService.getConversionRate("CHF", "EUR", saturday, "ECB");

    assertThat(provider.calls).isEmpty();
    assertThat(conversion.carriedForward()).isTrue();
    assertThat(conversion.stale()).isFalse();
    assertThat(conversion.rate())
        .isEqualByComparingTo(
            BigDecimal.ONE.divide(provider.rate("CHF", LocalDate.of(2026, 9, 11)), 10, HALF_UP));
  }

  @Test
  void aDateTheProviderHasNothingForIsFetchedOnceThenRefusedWithoutAsking() {
    LocalDate beforeTheSeries = LocalDate.of(1998, 6, 2);

    assertThat(fetching("USD", "CHF", beforeTheSeries)).isEmpty();
    assertThat(fetching("USD", "CHF", beforeTheSeries)).isEmpty();

    assertThat(provider.calls).hasSize(1);
  }

  @Test
  void aBatchTheDatabaseRejectsIsLoggedNotThrownIntoTheConversion(CapturedOutput output) {
    // A row fx_rate cannot hold (CHAR(3) currency) fails the whole chunk's insert.
    provider.unstorable = true;
    LocalDate date = LocalDate.of(2026, 2, 3);

    assertThat(fetching("USD", "CHF", date)).isEmpty();

    assertThat(provider.calls).hasSize(1);
    assertThat(fxRateRepository.count()).isZero();
    assertThat(output.getAll()).contains("FX fetch-on-missing for 2026-01-27");
  }

  @Test
  void aRateNotQuotedFromTheHubEndsTheRunBeforeItsChunkIsStored(CapturedOutput output) {
    // #226: cross rates are derived only from hub/x rows, so an x/hub answer is unreadable.
    provider.offHub = true;

    assertThat(importService.importLatest()).isZero();

    assertThat(fxRateRepository.count()).isZero();
    assertThat(output.getAll())
        .contains("returned USD/EUR, but quotes every other currency from its hub EUR");
  }

  @Test
  void theHubQuotedAgainstItselfEndsTheRunBeforeItsChunkIsStored(CapturedOutput output) {
    // #226: hub/hub is no rate - stored, it would only add a 1.0 row no cross rate needs.
    provider.hubToItself = true;

    assertThat(importService.importLatest()).isZero();

    assertThat(fxRateRepository.count()).isZero();
    assertThat(output.getAll())
        .contains("returned EUR/EUR, but quotes every other currency from its hub EUR");
  }

  @Test
  void anotherSourceNeverTriggersAFetch() {
    assertThat(fxRateService.tryGetConversionRateFetchingMissing("USD", "CHF", TODAY, "MANUAL"))
        .isEmpty();

    assertThat(provider.calls).isEmpty();
  }

  // Product owner, 2026-10-02: rates are master data, loaded in the background. Only a transaction
  // being created waits for the provider; every other read answers from what is stored.
  @Test
  void anyOtherReadNeverCallsTheProvider() {
    importService.importLatest();
    provider.calls.clear();
    LocalDate beforeEveryStoredRate = LocalDate.of(2026, 3, 10);

    assertThat(fxRateService.tryGetConversionRate("USD", "CHF", beforeEveryStoredRate, "ECB"))
        .isEmpty();

    assertThat(provider.calls).isEmpty();
  }

  // Dates of its own: the service remembers on-demand attempts in memory.
  @Test
  void aSecondRequestWaitsForARunningFetchAndUsesWhatItStored() throws Exception {
    importService.importLatest();
    provider.calls.clear();
    provider.gate = new CountDownLatch(1);

    CompletableFuture<Optional<CurrencyConversionResult>> first =
        CompletableFuture.supplyAsync(() -> fetching("USD", "CHF", LocalDate.of(2026, 4, 7)));
    awaitCalls(1);
    CompletableFuture<Optional<CurrencyConversionResult>> second =
        CompletableFuture.supplyAsync(() -> fetching("USD", "CHF", LocalDate.of(2026, 4, 9)));
    provider.gate.countDown();

    assertThat(first.get(10, TimeUnit.SECONDS)).isPresent();
    assertThat(second.get(10, TimeUnit.SECONDS)).isPresent();
    assertThat(provider.calls).hasSize(1);
  }

  // The request waiting for another's fetch holds its database connection meanwhile: waiting and
  // its own provider call share one on-demand budget instead of each getting the full timeout.
  @Test
  void waitingForAnotherFetchShortensTheWaitingRequestsOwnCall() throws Exception {
    importService.importLatest();
    provider.calls.clear();
    provider.gate = new CountDownLatch(1);

    CompletableFuture<Optional<CurrencyConversionResult>> first =
        CompletableFuture.supplyAsync(() -> fetching("USD", "CHF", LocalDate.of(2026, 5, 12)));
    awaitCalls(1);
    // Older than everything the first fetch loads, so it needs a provider call of its own.
    CompletableFuture<Optional<CurrencyConversionResult>> second =
        CompletableFuture.supplyAsync(() -> fetching("USD", "CHF", LocalDate.of(2025, 2, 11)));
    Thread.sleep(1500);
    provider.gate.countDown();

    assertThat(first.get(10, TimeUnit.SECONDS)).isPresent();
    assertThat(second.get(10, TimeUnit.SECONDS)).isPresent();
    assertThat(provider.onDemandTimeouts).hasSize(2);
    Duration budget = provider.onDemandTimeouts.get(0);
    assertThat(budget).isLessThanOrEqualTo(Duration.ofSeconds(20));
    assertThat(provider.onDemandTimeouts.get(1))
        .as("the second call gets only what its wait left of the budget")
        .isLessThanOrEqualTo(budget.minusMillis(1400));
  }

  @Test
  void crossRatesBetweenTheCurrenciesInUseAreStoredAsMasterData() {
    useCurrencies("CHF", "USD");

    importService.importLatest();

    // 12 published (EUR/CHF, EUR/USD on six weekdays) and, per day, CHF/USD, USD/CHF, CHF/EUR and
    // USD/EUR derived from them - EUR/x is published already.
    assertThat(fxRateRepository.count()).isEqualTo(12 + 4 * 6);
    assertThat(derivedRate("CHF", "USD", TODAY))
        .isEqualByComparingTo(
            provider.rate("USD", TODAY).divide(provider.rate("CHF", TODAY), 10, HALF_UP));
    assertThat(derivedRate("USD", "EUR", TODAY))
        .isEqualByComparingTo(BigDecimal.ONE.divide(provider.rate("USD", TODAY), 10, HALF_UP));

    CurrencyConversionResult conversion =
        fxRateService.getConversionRate("CHF", "USD", TODAY, "ECB");
    assertThat(conversion.rate()).isEqualByComparingTo(derivedRate("CHF", "USD", TODAY));
    assertThat(conversion.intermediateCurrency()).isEqualTo("EUR");
    assertThat(fxRateService.getConversionRate("USD", "EUR", TODAY, "ECB").direct()).isTrue();
  }

  @Test
  void theStoredCrossRateIsTheOneAConversionWouldOtherwiseCompute() {
    importService.importLatest();
    CurrencyConversionResult computed = fxRateService.getConversionRate("USD", "CHF", TODAY, "ECB");

    useCurrencies("CHF", "USD");
    importService.deriveCrossRatesForNewCurrencies();

    assertThat(derivedRate("USD", "CHF", TODAY)).isEqualByComparingTo(computed.rate());
  }

  @Test
  void aCurrencyNewlyInUseGetsItsCrossRatesOverTheWholeStoredHistory() {
    useCurrencies("CHF");
    importService.importLatest();
    assertThat(fxRateRepository.count()).isEqualTo(12 + 6); // CHF/EUR only
    provider.calls.clear();

    useCurrencies("USD"); // e.g. the first account in US dollars
    assertThat(importService.deriveCrossRatesForNewCurrencies()).isEqualTo(3 * 6);
    assertThat(importService.deriveCrossRatesForNewCurrencies()).isZero();

    assertThat(derivedRate("USD", "CHF", TODAY.minusDays(7))).isPositive();
    assertThat(provider.calls).isEmpty();
  }

  // Only the new currency's pairs are derived over the history: those between the others were
  // derived when they came into use, and redoing them would cost every pair on every day again.
  @Test
  void aCurrencyNewlyInUseDerivesOnlyItsOwnPairs() {
    useCurrencies("CHF");
    // As the job runs it: import, then mark the currencies in use as derived.
    importService.importLatest();
    importService.deriveCrossRatesForNewCurrencies();
    String chfEur =
        "FROM fx_rate WHERE derived AND base_currency = 'CHF' AND quote_currency = 'EUR'";
    assertThat(jdbcTemplate.update("DELETE " + chfEur)).isEqualTo(6);

    useCurrencies("USD");

    assertThat(importService.deriveCrossRatesForNewCurrencies())
        .as("USD/CHF, CHF/USD and USD/EUR on six weekdays")
        .isEqualTo(3 * 6);
    assertThat(jdbcTemplate.queryForObject("SELECT count(*) " + chfEur, Integer.class))
        .as("a pair of currencies already in use is not derived again")
        .isZero();
  }

  @Test
  void providerDownLeavesStoredRatesInUseMarkedStaleAndLogsWithTheRunsCorrelationId(
      CapturedOutput output) throws Exception {
    importService.importLatest();
    LocalDate twelveDaysLater = TODAY.plusDays(12);
    setToday(twelveDaysLater);
    provider.down = true;

    importJob().execute(jobContext());

    assertThat(fxRateRepository.findLatestRateDate("ECB")).contains(TODAY);
    CurrencyConversionResult conversion =
        fxRateService.getConversionRate("USD", "CHF", twelveDaysLater, "ECB");
    assertThat(conversion.carriedForward()).isTrue();
    assertThat(conversion.stale()).isTrue();
    assertThat(output.getAll())
        .containsPattern(
            "\\[[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\] .*scheduled FX import"
                + " for 2026-09-17 to 2026-09-28 failed");
  }

  // One job, three triggers: @DisallowConcurrentExecution then keeps every run apart (review of
  // PR #225, finding 6). Stored in V90's clustered JDBC store, which needs PostgreSQLDelegate.
  @Test
  void theImportJobIsRegisteredOnTheClusteredSchedulerWithItsThreeTriggers() throws Exception {
    JobKey job = JobKey.jobKey("import", FxRateImportJobConfig.JOB_GROUP);

    assertThat(scheduler.getJobDetail(job).getJobClass()).isEqualTo(FxRateImportJob.class);
    assertThat(scheduler.getJobDetail(job).isConcurrentExecutionDisallowed()).isTrue();
    assertThat(scheduler.getTriggersOfJob(job))
        .extracting(trigger -> trigger.getKey().getName())
        .containsExactlyInAnyOrder("scheduled-import", "startup-import", "history-check");
    CronTrigger scheduled =
        (CronTrigger)
            scheduler.getTrigger(
                TriggerKey.triggerKey("scheduled-import", FxRateImportJobConfig.JOB_GROUP));
    assertThat(scheduled.getCronExpression()).isEqualTo("0 0 0/2 * * ?"); // every two hours
    assertThat(scheduled.getTimeZone().getID()).isEqualTo("Europe/Berlin");
    assertThat(scheduled.getJobDataMap().getString(FxRateImportJob.MODE))
        .isEqualTo(FxRateImportJob.IMPORT);
    assertThat(
            scheduler
                .getTrigger(TriggerKey.triggerKey("history-check", FxRateImportJobConfig.JOB_GROUP))
                .getJobDataMap()
                .getString(FxRateImportJob.MODE))
        .isEqualTo(FxRateImportJob.HISTORY_CHECK);
  }

  private FxRateImportJob importJob() {
    return new FxRateImportJob(importService, transferRecheckService);
  }

  private Optional<CurrencyConversionResult> fetching(String base, String quote, LocalDate date) {
    return fxRateService.tryGetConversionRateFetchingMissing(base, quote, date, "ECB");
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
            + " AND rate_date = ? AND source = 'ECB' AND derived",
        BigDecimal.class,
        base,
        quote,
        date);
  }

  private void awaitCalls(int count) throws InterruptedException {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
    while (provider.calls.size() < count && System.nanoTime() < deadline) {
      Thread.sleep(10);
    }
    assertThat(provider.calls).hasSize(count);
  }

  // QuartzJobBean.execute binds the scheduler context and job data onto the job; both are empty.
  private static JobExecutionContext jobContext() throws Exception {
    JobExecutionContext context = mock(JobExecutionContext.class);
    Scheduler jobScheduler = mock(Scheduler.class);
    when(context.getScheduler()).thenReturn(jobScheduler);
    when(jobScheduler.getContext()).thenReturn(new SchedulerContext());
    when(context.getMergedJobDataMap()).thenReturn(new JobDataMap());
    return context;
  }

  private BigDecimal storedRate(String quote, LocalDate date) {
    return jdbcTemplate.queryForObject(
        "SELECT rate FROM fx_rate WHERE base_currency = 'EUR' AND quote_currency = ?"
            + " AND rate_date = ? AND source = 'ECB'",
        BigDecimal.class,
        quote,
        date);
  }

  private void setEarliestBooking(LocalDate date) {
    jdbcTemplate.update("UPDATE fx_rate_history_requirement SET earliest_booking_date = ?", date);
  }

  private void setToday(LocalDate date) {
    clock.set(Instant.parse(date + "T12:00:00Z"));
  }

  private static List<LocalDate> range(String from, String to) {
    return List.of(LocalDate.parse(from), LocalDate.parse(to));
  }

  private static long weekdays(LocalDate from, LocalDate to) {
    return from.datesUntil(to.plusDays(1)).filter(StubFxRateProvider::isWeekday).count();
  }

  @TestConfiguration
  static class StubProviderConfig {
    @Bean
    @Primary
    StubFxRateProvider stubFxRateProvider() {
      return new StubFxRateProvider();
    }
  }

  /**
   * Publishes EUR/CHF and EUR/USD on every weekday from 1999-01-04 (when the ECB series begins),
   * each a distinct value derived from its date, and records every requested range.
   */
  static class StubFxRateProvider implements FxRateProvider {

    private static final LocalDate ECB_SERIES_START = LocalDate.of(1999, 1, 4);

    final List<List<LocalDate>> calls = new CopyOnWriteArrayList<>();
    // The timeout each on-demand call got: what was left of its request's budget.
    final List<Duration> onDemandTimeouts = new CopyOnWriteArrayList<>();
    volatile boolean down;
    volatile boolean unstorable;
    volatile boolean offHub;
    volatile boolean hubToItself;
    volatile LocalDate seriesStart = ECB_SERIES_START;
    // When set, a fetch waits for it - a provider call still running.
    volatile CountDownLatch gate;

    void reset() {
      calls.clear();
      onDemandTimeouts.clear();
      gate = null;
      down = false;
      unstorable = false;
      offHub = false;
      hubToItself = false;
      seriesStart = ECB_SERIES_START;
    }

    @Override
    public FxRateProviderDefinition definition() {
      return EcbFxRateProvider.ECB_DEFINITION;
    }

    @Override
    public List<ProvidedFxRate> fetch(LocalDate from, LocalDate to) {
      calls.add(List.of(from, to));
      awaitGate();
      if (down) {
        throw new FxRateProviderException("ECB rates for " + from + " to " + to + " unavailable");
      }
      List<ProvidedFxRate> rates = new ArrayList<>();
      if (unstorable) {
        rates.add(new ProvidedFxRate("EUR", "CHFX", from, BigDecimal.ONE));
      }
      if (offHub) {
        rates.add(new ProvidedFxRate("USD", "EUR", from, BigDecimal.ONE));
      }
      if (hubToItself) {
        rates.add(new ProvidedFxRate("EUR", "EUR", from, BigDecimal.ONE));
      }
      from.datesUntil(to.plusDays(1))
          .filter(date -> isWeekday(date) && !date.isBefore(seriesStart))
          .forEach(
              date -> {
                rates.add(new ProvidedFxRate("EUR", "CHF", date, rate("CHF", date)));
                rates.add(new ProvidedFxRate("EUR", "USD", date, rate("USD", date)));
              });
      return rates;
    }

    @Override
    public List<ProvidedFxRate> fetchOnDemand(LocalDate from, LocalDate to, Duration timeout) {
      onDemandTimeouts.add(timeout);
      return fetch(from, to);
    }

    private void awaitGate() {
      CountDownLatch waitFor = gate;
      if (waitFor == null) {
        return;
      }
      try {
        waitFor.await(10, TimeUnit.SECONDS);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }

    BigDecimal rate(String currency, LocalDate date) {
      BigDecimal base = "CHF".equals(currency) ? new BigDecimal("0.9") : new BigDecimal("1.1");
      return base.add(new BigDecimal(date.getDayOfYear()).movePointLeft(4));
    }

    static boolean isWeekday(LocalDate date) {
      return date.getDayOfWeek() != DayOfWeek.SATURDAY && date.getDayOfWeek() != DayOfWeek.SUNDAY;
    }
  }
}
