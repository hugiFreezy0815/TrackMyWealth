package com.trackmywealth.backend.repository;

import com.trackmywealth.backend.entity.Account;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface AccountRepository extends JpaRepository<Account, UUID> {

  // Same SELECT ... FOR UPDATE pattern as AppUserRepository.findByIdForUpdate - serializes any
  // read-modify-write sequence keyed off this account (see AccountOwnershipService.assignOwnership,
  // the first caller) so a second concurrent writer blocks here until the first commits, rather
  // than both reading the same pre-change state and committing incompatible results neither one's
  // own single-request validation could have caught.
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("SELECT a FROM Account a WHERE a.id = :id")
  Optional<Account> findByIdForUpdate(@Param("id") UUID id);

  // US-04-03/FR-LIF-005: an institution summary counts only currently-active accounts - an
  // archived account is excluded from current totals (though still included in a historical
  // figure covering a period it was active in, not this story's concern).
  List<Account> findByFinancialInstitutionIdAndStatus(UUID financialInstitutionId, String status);
}
