package com.trackmywealth.backend.repository;

import com.trackmywealth.backend.entity.AccountSnapshot;
import jakarta.persistence.LockModeType;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface AccountSnapshotRepository extends JpaRepository<AccountSnapshot, UUID> {

  List<AccountSnapshot> findByAccountIdOrderBySnapshotDateDescSourceAsc(UUID accountId);

  Optional<AccountSnapshot> findByIdAndAccountId(UUID id, UUID accountId);

  Optional<AccountSnapshot> findByAccountIdAndSnapshotDateAndSource(
      UUID accountId, LocalDate snapshotDate, String source);

  // US-25-04: V58's partial unique index allows at most one.
  Optional<AccountSnapshot> findByAccountIdAndOpeningBalanceTrue(UUID accountId);

  // #232 review: the value of an account without a ledger - its newest balance on or before the
  // as-of date, the opening balance included. A holdings-only snapshot (no balance) says nothing
  // about it.
  Optional<AccountSnapshot>
      findFirstByAccountIdAndSnapshotDateLessThanEqualAndBalanceIsNotNullOrderBySnapshotDateDescCreatedAtDesc(
          UUID accountId, LocalDate asOf);

  // Replacing a snapshot deletes and re-inserts its holdings; the row lock keeps two concurrent
  // replacements from interleaving into a mix of both holding sets.
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("SELECT s FROM AccountSnapshot s WHERE s.id = :id AND s.account.id = :accountId")
  Optional<AccountSnapshot> findForUpdate(@Param("id") UUID id, @Param("accountId") UUID accountId);
}
