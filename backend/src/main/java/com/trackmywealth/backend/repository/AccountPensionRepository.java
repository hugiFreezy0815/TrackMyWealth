package com.trackmywealth.backend.repository;

import com.trackmywealth.backend.entity.AccountPension;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface AccountPensionRepository extends JpaRepository<AccountPension, UUID> {}
