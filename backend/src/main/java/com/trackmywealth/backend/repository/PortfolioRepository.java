package com.trackmywealth.backend.repository;

import com.trackmywealth.backend.entity.Portfolio;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface PortfolioRepository extends JpaRepository<Portfolio, UUID> {

  // V19's workspace_create_personal_assets_container trigger creates exactly one of these per
  // workspace immediately after the workspace row is inserted; SetupService reads it back to
  // correct its placeholder CHF currency to the workspace's actual chosen currency.
  Optional<Portfolio> findByWorkspaceIdAndPersonalAssetsDefaultTrue(UUID workspaceId);

  /**
   * US-04-06: serialises the refresh of one portfolio's suggested type, so two transactions that
   * each add an account cannot both compute the type without the other's account. {@code FOR NO KEY
   * UPDATE}, not {@code FOR UPDATE}: it must not wait for the {@code KEY SHARE} lock the
   * foreign-key check of a concurrent account insert holds on the same row until it commits.
   *
   * @return whether the member has set the portfolio's type ({@code portfolio_type_overridden}),
   *     read under the lock; empty when RLS hides the row or it does not exist
   */
  @Query(
      value = "SELECT portfolio_type_overridden FROM portfolio WHERE id = :id FOR NO KEY UPDATE",
      nativeQuery = true)
  Optional<Boolean> lockForTypeRefresh(@Param("id") UUID id);

  /**
   * US-04-06: stores {@code type} as the portfolio's suggested type unless the member has set one
   * or it already has it - so an unchanged type does not bump the portfolio's version. Call it
   * under {@link #lockForTypeRefresh}.
   */
  @Modifying(flushAutomatically = true)
  @Query(
      value =
          "UPDATE portfolio SET portfolio_type = :type WHERE id = :id"
              + " AND NOT portfolio_type_overridden AND portfolio_type <> :type",
      nativeQuery = true)
  int applySuggestedType(@Param("id") UUID id, @Param("type") String type);
}
