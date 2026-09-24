package com.trackmywealth.backend.repository;

import com.trackmywealth.backend.entity.SecurityAssetClassWeight;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface SecurityAssetClassWeightRepository
    extends JpaRepository<SecurityAssetClassWeight, UUID> {

  /**
   * The weights in force today, heaviest first. Effective-dated (FR-SMD-009): a security may carry
   * several vintages of its classification, so a query that ignored {@code effective_date} would
   * mix them and could report a superseded asset class as the dominant one. Only one vintage exists
   * per security today (a manual record gets a single 100% row), which is exactly why this is worth
   * pinning now - the bug would appear the first time a provider or a reclassification writes a
   * second one, far from this code.
   *
   * <p>{@code CURRENT_DATE} rather than a date passed in from Java: both sides of the comparison
   * then come from the database's clock, so a server in a different time zone from the database
   * cannot see a row flip in or out around midnight. Ties are broken by asset class purely so the
   * "dominant" pick is stable across calls rather than left to the scan order.
   */
  @Query(
      "SELECT w FROM SecurityAssetClassWeight w"
          + " WHERE w.securityId = :securityId AND w.effectiveDate <= CURRENT_DATE"
          + " ORDER BY w.effectiveDate DESC, w.weight DESC, w.assetClass ASC")
  List<SecurityAssetClassWeight> findEffectiveBySecurityId(@Param("securityId") UUID securityId);
}
