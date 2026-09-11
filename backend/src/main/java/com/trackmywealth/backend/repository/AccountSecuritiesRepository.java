package com.trackmywealth.backend.repository;

import com.trackmywealth.backend.entity.AccountSecurities;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface AccountSecuritiesRepository extends JpaRepository<AccountSecurities, UUID> {}
