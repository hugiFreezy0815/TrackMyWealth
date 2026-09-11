package com.trackmywealth.backend.repository;

import com.trackmywealth.backend.entity.AccountVestedBenefits;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface AccountVestedBenefitsRepository
    extends JpaRepository<AccountVestedBenefits, UUID> {}
