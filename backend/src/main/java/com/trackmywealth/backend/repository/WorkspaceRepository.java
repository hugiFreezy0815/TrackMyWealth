package com.trackmywealth.backend.repository;

import com.trackmywealth.backend.entity.Workspace;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface WorkspaceRepository extends JpaRepository<Workspace, UUID> {

  // Same SELECT ... FOR UPDATE pattern as AccountRepository/AppUserRepository's own
  // findByIdForUpdate - #122: serializes SharingGrantService's bootstrap check-then-insert for
  // WORKSPACE scope, so two concurrent grant() calls can't both observe "no existing grant" and
  // both bypass the FULL-access requirement.
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("SELECT w FROM Workspace w WHERE w.id = :id")
  Optional<Workspace> findByIdForUpdate(@Param("id") UUID id);
}
