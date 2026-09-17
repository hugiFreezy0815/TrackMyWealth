package com.trackmywealth.backend.repository;

import com.trackmywealth.backend.entity.CustomAssetValuation;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface CustomAssetValuationRepository extends JpaRepository<CustomAssetValuation, UUID> {

  List<CustomAssetValuation> findByAccountIdOrderByValuationDateDesc(UUID accountId);

  // FR-NW-003 AC #2/PR-011: the valuation applicable to a given date is the latest one dated on
  // or before it, never an interpolation between two known points (same "carry forward, never
  // interpolate" rule FR-PRC-011 states for prices).
  Optional<CustomAssetValuation>
      findFirstByAccountIdAndValuationDateLessThanEqualOrderByValuationDateDesc(
          UUID accountId, LocalDate asOfDate);

  // US-04-03: the bulk equivalent of the single-account query above, for a caller (
  // InstitutionSummaryService) resolving many accounts' current values in one summary request - one
  // query instead of one per account. Globally ordered by valuationDate DESC (not grouped by
  // account first), so a caller must pick, per accountId, only the first row it encounters while
  // iterating - by that same global ordering, the first row seen for any given accountId is
  // necessarily that account's own latest valuation on or before asOfDate, regardless of how other
  // accounts' rows are interleaved.
  List<CustomAssetValuation> findByAccountIdInAndValuationDateLessThanEqualOrderByValuationDateDesc(
      List<UUID> accountIds, LocalDate asOfDate);
}
