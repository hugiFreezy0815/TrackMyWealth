package com.trackmywealth.backend.repository;

import com.trackmywealth.backend.entity.FinancialInstitution;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface FinancialInstitutionRepository extends JpaRepository<FinancialInstitution, UUID> {

  // V19's workspace_create_personal_assets_container trigger creates exactly one of these per
  // workspace immediately after the workspace row is inserted; SetupService reads it back to
  // correct its placeholder CHF currency to the workspace's actual chosen currency.
  Optional<FinancialInstitution> findByWorkspaceIdAndPersonalAssetsDefaultTrue(UUID workspaceId);

  // Same SELECT ... FOR UPDATE pattern as AccountRepository/AppUserRepository's own
  // findByIdForUpdate - #122: serializes SharingGrantService's bootstrap check-then-insert for this
  // institution, so two concurrent grant() calls can't both observe "no existing grant" and both
  // bypass the FULL-access requirement.
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("SELECT i FROM FinancialInstitution i WHERE i.id = :id")
  Optional<FinancialInstitution> findByIdForUpdate(@Param("id") UUID id);
}
