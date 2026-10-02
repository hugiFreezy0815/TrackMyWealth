package com.trackmywealth.backend.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.trackmywealth.backend.client.FxRateProvider;
import com.trackmywealth.backend.client.FxRateProviderException;
import com.trackmywealth.backend.client.ProvidedFxRate;
import com.trackmywealth.backend.config.FxRateImportProperties;
import com.trackmywealth.backend.entity.FxRate;
import com.trackmywealth.backend.repository.FxRateBatchRepository;
import com.trackmywealth.backend.repository.FxRateHistoryRequirementRepository;
import com.trackmywealth.backend.repository.FxRateRepository;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * US-06-04 (#223): fills {@code fx_rate} from the configured {@link FxRateProvider} (the ECB) so
 * that no conversion depends on rates entered by hand. FX rates are master data (product owner,
 * 2026-10-02): loaded in the background, and read, never fetched, by calculations. Entry points:
 *
 * <ul>
 *   <li>{@link #importLatest} - the scheduled import, every two hours: every day since the last
 *       stored one, up to today. On the very first run that is the full history from the first
 *       transaction booking on.
 *   <li>{@link #backfillHistory} - the history check, every few minutes: when a transaction older
 *       than the stored history has been booked (an import of old statements), loads the rates from
 *       that day on.
 *   <li>{@link #deriveCrossRatesForNewCurrencies} - after either: a currency newly in use gets its
 *       cross rates over the whole stored history.
 *   <li>{@link #fetchMissingHistory} - the one call a request waits for: a transaction created for
 *       a date before every stored rate, whose rate is fixed at insert.
 * </ul>
 *
 * <p>Every chunk stored also stores the cross rates between the currencies in use for its days
 * ({@code FxRateBatchRepository#deriveCrossRates}, V54), so a conversion between two of them is a
 * lookup of a stored rate.
 *
 * <p>Every provider call covers {@value #LOOKBACK_DAYS} days before the date it is for, so a date
 * that falls on a weekend or a holiday still finds the last published rate to carry forward
 * (FR-CUR-012). A day that is already stored is never stored again ({@code
 * FxRateBatchRepository#insertIfAbsent}); a date with no published rate is simply absent and never
 * fetched again by the daily job, which only moves forward.
 *
 * <p>Failures never propagate (NFR-CON-003, PR-012) - neither the provider's nor one storing what
 * it returned: stored rates stay in use - {@code FxRateService} marks them stale once they are old
 * enough - and the failure is logged, with the request's or the job run's correlation id from the
 * MDC. Each chunk of at most {@value #CHUNK_DAYS} days is fetched and stored in its own
 * transaction, so a long backfill that fails halfway keeps what it stored, and a caller's
 * transaction is never written to.
 */
@Service
public class FxRateImportService {

  private static final Logger LOG = LoggerFactory.getLogger(FxRateImportService.class);

  static final int LOOKBACK_DAYS = 7;
  static final int CHUNK_DAYS = 366;
  // The ECB's longest regular pause: Maundy Thursday to the Tuesday after Easter.
  // app.fx.stale-after
  // (FxRateService) defaults to the same five days for the same reason.
  static final int MAX_PUBLICATION_GAP_DAYS = 5;
  private static final LocalDate ALL_HISTORY_FROM = LocalDate.of(1900, 1, 1);
  private static final LocalDate ALL_HISTORY_TO = LocalDate.of(9999, 12, 31);
  private static final int MAX_REMEMBERED_ON_DEMAND_DATES = 10_000;
  // Less than this left of a request's on-demand budget is not worth a provider call.
  static final Duration MIN_ON_DEMAND_CALL = Duration.ofSeconds(1);

  private final FxRateProvider provider;
  private final FxRateRepository fxRateRepository;
  private final FxRateBatchRepository fxRateBatchRepository;
  private final FxRateHistoryRequirementRepository historyRequirementRepository;
  private final BusinessDateService businessDateService;
  private final FxRateImportProperties properties;
  private final TransactionTemplate ownTransaction;

  // Fetch-on-missing runs inside a user's request: one fetch at a time per instance - a second
  // request waits for it rather than fetching the same year again - and a date that found nothing
  // is not asked for again until onDemandRetryAfter has passed.
  private final ReentrantLock onDemandLock = new ReentrantLock();
  private final Cache<LocalDate, Boolean> onDemandAttempts;
  // The oldest history start the provider had nothing for (e.g. before its series begins), so
  // the hourly check does not ask for it again until an even older booking arrives.
  private final AtomicReference<LocalDate> exhaustedHistoryStart = new AtomicReference<>();

  public FxRateImportService(
      FxRateProvider provider,
      FxRateRepository fxRateRepository,
      FxRateBatchRepository fxRateBatchRepository,
      FxRateHistoryRequirementRepository historyRequirementRepository,
      BusinessDateService businessDateService,
      FxRateImportProperties properties,
      PlatformTransactionManager transactionManager) {
    this.provider = provider;
    this.fxRateRepository = fxRateRepository;
    this.fxRateBatchRepository = fxRateBatchRepository;
    this.historyRequirementRepository = historyRequirementRepository;
    this.businessDateService = businessDateService;
    this.properties = properties;
    this.ownTransaction = new TransactionTemplate(transactionManager);
    this.ownTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    this.onDemandAttempts =
        Caffeine.newBuilder()
            .expireAfterWrite(properties.onDemandRetryAfter())
            .maximumSize(MAX_REMEMBERED_ON_DEMAND_DATES)
            .build();
  }

  /** The {@code fx_rate.source} this import stores under. */
  public String source() {
    return provider.source();
  }

  /** Whether fetch-on-missing applies to conversions against {@code source}. */
  public boolean fetchesOnDemandFor(String source) {
    return properties.enabled() && provider.source().equals(source);
  }

  /**
   * The daily import: every day after the latest stored rate up to today. With nothing stored yet,
   * the full history from the first transaction booking on (or the last {@value #LOOKBACK_DAYS}
   * days while the ledger is empty).
   *
   * @return how many rates were stored
   */
  public int importLatest() {
    LocalDate today = businessDateService.today();
    LocalDate from =
        fxRateRepository
            .findLatestRateDate(provider.source())
            .map(latest -> latest.plusDays(1))
            .orElseGet(() -> historyStart(today));
    return importRange(from, today, "scheduled FX import", null);
  }

  /**
   * The history check: when the earliest transaction booking is older than the unbroken run of
   * stored rates that reaches up to the latest one, loads the rates from {@value #LOOKBACK_DAYS}
   * days before that booking up to the start of the run. A run is broken by more than {@value
   * #MAX_PUBLICATION_GAP_DAYS} days without a rate - longer than any weekend plus holiday - so the
   * gap a one-year fetch-on-missing can leave behind it is filled here too. Makes no provider call
   * otherwise, so it is cheap to run often.
   *
   * @return how many rates were stored
   */
  public int backfillHistory() {
    Optional<LocalDate> required = historyRequirementRepository.findEarliestBookingDate();
    Optional<LocalDate> runStart =
        fxRateRepository.findStartOfLatestRun(provider.source(), MAX_PUBLICATION_GAP_DAYS);
    if (required.isEmpty() || runStart.isEmpty()) {
      // Nothing booked needs history, or nothing is stored yet - the daily import's first run
      // loads the full history itself.
      return 0;
    }
    LocalDate from = required.get().minusDays(LOOKBACK_DAYS);
    if (!required.get().isBefore(runStart.get()) || from.equals(exhaustedHistoryStart.get())) {
      return 0;
    }
    int stored = importRange(from, runStart.get().minusDays(1), "FX history backfill", null);
    if (stored == 0) {
      exhaustedHistoryStart.set(from);
    }
    return stored;
  }

  /**
   * Fetch-on-missing, for a transaction being created: when {@code date} lies before every stored
   * rate, loads the rates from {@value #LOOKBACK_DAYS} days before it up to the earliest stored one
   * - at most {@value #CHUNK_DAYS} days of them, one provider call with the on-demand timeout. A
   * gap left between those and the stored history is filled by {@link #backfillHistory}. Does
   * nothing for a date that already has a stored rate on or before it - such a conversion carries
   * that rate forward (FR-CUR-012) - and never throws.
   *
   * <p>While another request's fetch runs, waits for it and then checks again, so the second of two
   * requests for the same old year is answered from what the first stored instead of failing.
   * Waiting and the provider call share one budget, {@code app.fx.import.on-demand-read-timeout}:
   * the request holds its database connection meanwhile, so it must not wait twice that long.
   *
   * @return {@code true} when rates now cover {@code date}, so a lookup is worth repeating
   */
  public boolean fetchMissingHistory(LocalDate date) {
    if (!properties.enabled() || isCovered(date)) {
      return false;
    }
    long budgetEnd = System.nanoTime() + properties.onDemandReadTimeout().toNanos();
    try {
      if (!onDemandLock.tryLock(properties.onDemandReadTimeout().toNanos(), TimeUnit.NANOSECONDS)) {
        return false;
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return false;
    }
    try {
      Optional<LocalDate> earliestStored = fxRateRepository.findEarliestRateDate(provider.source());
      if (covers(earliestStored, date)) {
        return true; // stored by the request this one waited for
      }
      Duration remaining = Duration.ofNanos(budgetEnd - System.nanoTime());
      if (remaining.compareTo(MIN_ON_DEMAND_CALL) < 0) {
        return false; // the wait used up the budget; the background import loads it instead
      }
      if (onDemandAttempts.asMap().putIfAbsent(date, Boolean.TRUE) != null) {
        return false;
      }
      LocalDate from = date.minusDays(LOOKBACK_DAYS);
      LocalDate to =
          min(
              earliestStored.map(d -> d.minusDays(1)).orElseGet(businessDateService::today),
              from.plusDays(CHUNK_DAYS - 1L));
      return importRange(from, to, "FX fetch-on-missing", remaining) > 0;
    } finally {
      onDemandLock.unlock();
    }
  }

  /**
   * Stores the cross rates of every currency that came into use since the last run (V54) over the
   * whole stored history - new pairs of an account, transaction or institution in a currency not
   * used before. Never throws.
   *
   * @return how many cross rates were stored
   */
  public int deriveCrossRatesForNewCurrencies() {
    if (!properties.enabled()) {
      return 0;
    }
    try {
      Integer stored =
          ownTransaction.execute(
              status -> {
                List<UUID> pending = fxRateBatchRepository.findCurrenciesNotYetDerived();
                if (pending.isEmpty()) {
                  return 0;
                }
                int derived =
                    fxRateBatchRepository.deriveCrossRatesOfNewCurrencies(
                        provider.source(), ALL_HISTORY_FROM, ALL_HISTORY_TO);
                fxRateBatchRepository.markCrossRatesDerived(pending);
                return derived;
              });
      return stored == null ? 0 : stored;
    } catch (DataAccessException | TransactionException e) {
      if (LOG.isWarnEnabled()) {
        LOG.warn("Deriving FX cross rates for new currencies failed: {}", e.getMessage(), e);
      }
      return 0;
    }
  }

  private boolean isCovered(LocalDate date) {
    return covers(fxRateRepository.findEarliestRateDate(provider.source()), date);
  }

  private static boolean covers(Optional<LocalDate> earliestStored, LocalDate date) {
    return earliestStored.isPresent() && !date.isBefore(earliestStored.get());
  }

  private LocalDate historyStart(LocalDate today) {
    LocalDate start =
        historyRequirementRepository
            .findEarliestBookingDate()
            .orElse(today)
            .minusDays(LOOKBACK_DAYS);
    return start.isAfter(today) ? today : start;
  }

  // onDemandTimeout: what is left of a waiting request's budget, or null for background work.
  private int importRange(LocalDate from, LocalDate to, String purpose, Duration onDemandTimeout) {
    if (!properties.enabled() || from.isAfter(to)) {
      return 0;
    }
    int stored = 0;
    LocalDate chunkStart = from;
    try {
      while (!chunkStart.isAfter(to)) {
        LocalDate chunkEnd = min(chunkStart.plusDays(CHUNK_DAYS - 1L), to);
        stored += importChunk(chunkStart, chunkEnd, onDemandTimeout);
        chunkStart = chunkEnd.plusDays(1);
      }
    } catch (FxRateProviderException | DataAccessException | TransactionException e) {
      if (LOG.isWarnEnabled()) {
        LOG.warn(
            "{} for {} to {} failed after storing {} rate(s); stored rates stay in use: {}",
            purpose,
            from,
            to,
            stored,
            e.getMessage(),
            e);
      }
      return stored;
    }
    if (stored > 0 && LOG.isInfoEnabled()) {
      LOG.info("{} stored {} {} rate(s) for {} to {}", purpose, stored, source(), from, to);
    }
    return stored;
  }

  // Published rates and the cross rates derived from them are committed together.
  private int importChunk(LocalDate from, LocalDate to, Duration onDemandTimeout) {
    List<ProvidedFxRate> provided =
        onDemandTimeout == null
            ? provider.fetch(from, to)
            : provider.fetchOnDemand(from, to, onDemandTimeout);
    List<FxRate> rates = provided.stream().map(this::toEntity).toList();
    if (rates.isEmpty()) {
      return 0;
    }
    Integer stored =
        ownTransaction.execute(
            status -> {
              int inserted = fxRateBatchRepository.insertIfAbsent(rates);
              if (inserted > 0) {
                fxRateBatchRepository.deriveCrossRates(provider.source(), from, to);
              }
              return inserted;
            });
    return stored == null ? 0 : stored;
  }

  private FxRate toEntity(ProvidedFxRate provided) {
    FxRate rate = new FxRate();
    rate.setBaseCurrency(provided.baseCurrency());
    rate.setQuoteCurrency(provided.quoteCurrency());
    rate.setRateDate(provided.rateDate());
    rate.setRate(provided.rate());
    rate.setSource(provider.source());
    return rate;
  }

  private static LocalDate min(LocalDate a, LocalDate b) {
    return a.isBefore(b) ? a : b;
  }
}
