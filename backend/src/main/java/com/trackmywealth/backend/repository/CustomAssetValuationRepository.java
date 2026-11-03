package com.trackmywealth.backend.repository;

import com.trackmywealth.backend.entity.CustomAssetValuation;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
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

  /**
   * #380: {@link #findFirstByAccountIdAndValuationDateLessThanEqualOrderByValuationDateDesc} for
   * several accounts in one query - per account, the latest valuation dated on or before {@code
   * asOfDate}. An account without one has no row.
   */
  @Query(
      value =
          """
          SELECT DISTINCT ON (account_id) *
          FROM custom_asset_valuation
          WHERE account_id IN (:accountIds) AND valuation_date <= :asOfDate
          ORDER BY account_id, valuation_date DESC, created_at DESC, id
          """,
      nativeQuery = true)
  List<CustomAssetValuation> findLatestOnOrBefore(
      @Param("accountIds") Collection<UUID> accountIds, @Param("asOfDate") LocalDate asOfDate);
}
