package com.trackmywealth.backend.repository;

import com.trackmywealth.backend.entity.AccountOwnership;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface AccountOwnershipRepository extends JpaRepository<AccountOwnership, UUID> {

  List<AccountOwnership> findByAccountIdAndEffectiveToIsNull(UUID accountId);
}
