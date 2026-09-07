package com.trackmywealth.backend.repository;

import com.trackmywealth.backend.entity.AppUser;
import com.trackmywealth.backend.security.AppUserAuthSnapshot;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface AppUserRepository extends JpaRepository<AppUser, UUID> {

  // citext equality is already case-insensitive at the database level, so a plain `= ?` here
  // gives the right semantics without needing an IgnoreCase-suffixed method name.
  boolean existsByEmail(String email);

  @Query(
      "SELECT new com.trackmywealth.backend.security.AppUserAuthSnapshot("
          + "u.id, u.role, u.status, u.tokenVersion, h.id) "
          + "FROM AppUser u LEFT JOIN u.householdMember hm LEFT JOIN hm.household h "
          + "WHERE u.id = :userId")
  Optional<AppUserAuthSnapshot> findAuthSnapshot(@Param("userId") UUID userId);

  // US-02-01's last-active-administrator guard (FR-USR-005): a row-level lock over every
  // currently active administrator, not a DB constraint - see the comment on this rule in V2 and
  // in the story itself. Must be called inside the same @Transactional method that then performs
  // the disable/demote, so the lock is held until that method's own commit. PostgreSQL rejects
  // FOR UPDATE directly on an aggregate query ("FOR UPDATE is not allowed with aggregate
  // functions") - the inner subquery locks the actual rows, the outer aggregate then just counts
  // the (already-locked) result.
  @Query(
      value =
          "SELECT count(*) FROM (SELECT id FROM app_user WHERE role = 'SYSTEM_ADMINISTRATOR' AND"
              + " status = 'ACTIVE' FOR UPDATE) locked_active_administrators",
      nativeQuery = true)
  long countActiveAdministratorsForUpdate();
}
