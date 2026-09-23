package com.trackmywealth.backend.repository;

import com.trackmywealth.backend.entity.SecurityAssetClassWeight;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface SecurityAssetClassWeightRepository
    extends JpaRepository<SecurityAssetClassWeight, UUID> {

  List<SecurityAssetClassWeight> findBySecurityIdOrderByWeightDesc(UUID securityId);
}
