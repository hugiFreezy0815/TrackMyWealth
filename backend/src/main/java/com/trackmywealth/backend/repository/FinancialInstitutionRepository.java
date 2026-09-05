package com.trackmywealth.backend.repository;

import com.trackmywealth.backend.entity.FinancialInstitution;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface FinancialInstitutionRepository extends JpaRepository<FinancialInstitution, UUID> {

  // V19's household_create_personal_assets_container trigger creates exactly one of these per
  // household immediately after the household row is inserted; SetupService reads it back to
  // correct its placeholder CHF currency to the household's actual chosen currency.
  Optional<FinancialInstitution> findByHouseholdIdAndPersonalAssetsDefaultTrue(UUID householdId);
}
