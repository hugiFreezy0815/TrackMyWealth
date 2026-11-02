package com.trackmywealth.backend.repository;

import com.trackmywealth.backend.entity.Portfolio;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface PortfolioRepository extends JpaRepository<Portfolio, UUID> {

  // V19's workspace_create_personal_assets_container trigger creates exactly one of these per
  // workspace immediately after the workspace row is inserted; SetupService reads it back to
  // correct its placeholder CHF currency to the workspace's actual chosen currency.
  Optional<Portfolio> findByWorkspaceIdAndPersonalAssetsDefaultTrue(UUID workspaceId);
}
