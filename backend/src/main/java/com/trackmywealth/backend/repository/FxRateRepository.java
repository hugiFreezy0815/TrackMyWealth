package com.trackmywealth.backend.repository;

import com.trackmywealth.backend.entity.FxRate;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
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
}
