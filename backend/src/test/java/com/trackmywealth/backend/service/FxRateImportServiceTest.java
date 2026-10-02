package com.trackmywealth.backend.service;

import static java.math.RoundingMode.HALF_UP;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.trackmywealth.backend.client.FxRateProvider;
import com.trackmywealth.backend.client.FxRateProviderException;
import com.trackmywealth.backend.client.ProvidedFxRate;
import com.trackmywealth.backend.config.FxRateImportJobConfig;
import com.trackmywealth.backend.dto.CurrencyConversionResult;
import com.trackmywealth.backend.entity.FxRate;
import com.trackmywealth.backend.job.FxRateDailyImportJob;
import com.trackmywealth.backend.repository.FxRateBatchRepository;
import com.trackmywealth.backend.repository.FxRateRepository;
import com.trackmywealth.backend.testsupport.MutableClock;
import com.trackmywealth.backend.testsupport.TestClockConfig;
import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
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
 * publishes EUR/CHF and EUR/USD on every weekday: the daily import, the first run's full history,
 * the history backfill after an older booking, fetch-on-missing, the weekend carry-forward that
 * fetches nothing, and the provider-down fallback to the last stored rate, marked stale.
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

  @BeforeEach
  void setUp() {
    setToday(TODAY);
    provider.reset();
  }

  @AfterEach
  void cleanDatabase() {
    fxRateRepository.deleteAll();
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
  void conversionForADateBeforeEveryStoredRateFetchesStoresAndUsesIt() {
    importService.importLatest();
    provider.calls.clear();
    LocalDate tuesday = LocalDate.of(2026, 3, 10);

    CurrencyConversionResult conversion =
        fxRateService.getConversionRate("USD", "CHF", tuesday, "ECB");

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

    fxRateService.getConversionRate("USD", "CHF", oldBooking, "ECB");

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

    assertThat(fxRateService.tryGetConversionRate("USD", "CHF", beforeTheSeries, "ECB")).isEmpty();
    assertThat(fxRateService.tryGetConversionRate("USD", "CHF", beforeTheSeries, "ECB")).isEmpty();

    assertThat(provider.calls).hasSize(1);
  }

  @Test
  void aBatchTheDatabaseRejectsIsLoggedNotThrownIntoTheConversion(CapturedOutput output) {
    // A row fx_rate cannot hold (CHAR(3) currency) fails the whole chunk's insert.
    provider.unstorable = true;
    LocalDate date = LocalDate.of(2026, 2, 3);

    assertThat(fxRateService.tryGetConversionRate("USD", "CHF", date, "ECB")).isEmpty();

    assertThat(provider.calls).hasSize(1);
    assertThat(fxRateRepository.count()).isZero();
    assertThat(output.getAll()).contains("FX fetch-on-missing for 2026-01-27");
  }

  @Test
  void anotherSourceNeverTriggersAFetch() {
    assertThat(fxRateService.tryGetConversionRate("USD", "CHF", TODAY, "MANUAL")).isEmpty();

    assertThat(provider.calls).isEmpty();
  }

  @Test
  void providerDownLeavesStoredRatesInUseMarkedStaleAndLogsWithTheRunsCorrelationId(
      CapturedOutput output) throws Exception {
    importService.importLatest();
    LocalDate twelveDaysLater = TODAY.plusDays(12);
    setToday(twelveDaysLater);
    provider.down = true;

    dailyJob().execute(jobContext());

    assertThat(fxRateRepository.findLatestRateDate("ECB")).contains(TODAY);
    CurrencyConversionResult conversion =
        fxRateService.getConversionRate("USD", "CHF", twelveDaysLater, "ECB");
    assertThat(conversion.carriedForward()).isTrue();
    assertThat(conversion.stale()).isTrue();
    assertThat(output.getAll())
        .containsPattern(
            "\\[[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\] .*daily FX import"
                + " for 2026-09-17 to 2026-09-28 failed");
  }

  @Test
  void bothJobsAreRegisteredOnTheClusteredScheduler() throws Exception {
    JobKey daily = JobKey.jobKey("daily-import", FxRateImportJobConfig.JOB_GROUP);
    JobKey history = JobKey.jobKey("history-check", FxRateImportJobConfig.JOB_GROUP);

    assertThat(scheduler.getJobDetail(daily).getJobClass()).isEqualTo(FxRateDailyImportJob.class);
    assertThat(scheduler.checkExists(history)).isTrue();
    CronTrigger trigger =
        (CronTrigger)
            scheduler.getTrigger(
                TriggerKey.triggerKey("daily-import", FxRateImportJobConfig.JOB_GROUP));
    assertThat(trigger.getCronExpression()).isEqualTo("0 30 16 * * ?");
    assertThat(trigger.getTimeZone().getID()).isEqualTo("Europe/Berlin");
    assertThat(
            scheduler.checkExists(
                TriggerKey.triggerKey("startup-import", FxRateImportJobConfig.JOB_GROUP)))
        .isTrue();
  }

  private FxRateDailyImportJob dailyJob() {
    return new FxRateDailyImportJob(importService);
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
    volatile boolean down;
    volatile boolean unstorable;
    volatile LocalDate seriesStart = ECB_SERIES_START;

    void reset() {
      calls.clear();
      down = false;
      unstorable = false;
      seriesStart = ECB_SERIES_START;
    }

    @Override
    public String source() {
      return "ECB";
    }

    @Override
    public List<ProvidedFxRate> fetch(LocalDate from, LocalDate to) {
      calls.add(List.of(from, to));
      if (down) {
        throw new FxRateProviderException("ECB rates for " + from + " to " + to + " unavailable");
      }
      List<ProvidedFxRate> rates = new ArrayList<>();
      if (unstorable) {
        rates.add(new ProvidedFxRate("EUR", "CHFX", from, BigDecimal.ONE));
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

    BigDecimal rate(String currency, LocalDate date) {
      BigDecimal base = "CHF".equals(currency) ? new BigDecimal("0.9") : new BigDecimal("1.1");
      return base.add(new BigDecimal(date.getDayOfYear()).movePointLeft(4));
    }

    static boolean isWeekday(LocalDate date) {
      return date.getDayOfWeek() != DayOfWeek.SATURDAY && date.getDayOfWeek() != DayOfWeek.SUNDAY;
    }
  }
}
