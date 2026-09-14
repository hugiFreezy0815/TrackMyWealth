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
}
