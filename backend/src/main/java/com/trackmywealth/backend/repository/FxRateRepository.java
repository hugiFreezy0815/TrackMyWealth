package com.trackmywealth.backend.repository;

import com.trackmywealth.backend.entity.FxRate;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

@Repository
public interface FxRateRepository extends JpaRepository<FxRate, UUID> {

  // US-06-01 AC#1/#3: the most recent rate on or before the requested date for a given
  // pair/source - the same row whether the match is exact (requested date has a rate) or a
  // carry-forward (it doesn't); FxRateService tells the two apart by comparing the returned
  // row's rateDate to the date that was asked for.
  Optional<FxRate>
      findFirstByBaseCurrencyAndQuoteCurrencyAndSourceAndRateDateLessThanEqualOrderByRateDateDesc(
          String baseCurrency, String quoteCurrency, String source, LocalDate rateDate);

  // US-06-04 (#223): the stored range of one source - where the daily import resumes, and whether
  // a conversion's date lies before everything stored so far (fetch-on-missing).
  @Query("SELECT max(f.rateDate) FROM FxRate f WHERE f.source = :source")
  Optional<LocalDate> findLatestRateDate(String source);

  @Query("SELECT min(f.rateDate) FROM FxRate f WHERE f.source = :source")
  Optional<LocalDate> findEarliestRateDate(String source);

  // US-06-04 (#223): the first date of the newest unbroken run of stored dates - the latest date
  // that follows its predecessor by more than maxGapDays (or has none). The history backfill fills
  // from here backwards, which also closes a gap a bounded fetch-on-missing left behind it.
  @Query(
      value =
          "SELECT max(rate_date) FROM ("
              + " SELECT rate_date, rate_date - lag(rate_date) OVER (ORDER BY rate_date) AS gap"
              + " FROM (SELECT DISTINCT rate_date FROM fx_rate WHERE source = :source) dates"
              + ") runs WHERE gap IS NULL OR gap > :maxGapDays",
      nativeQuery = true)
  Optional<LocalDate> findStartOfLatestRun(String source, int maxGapDays);
}
