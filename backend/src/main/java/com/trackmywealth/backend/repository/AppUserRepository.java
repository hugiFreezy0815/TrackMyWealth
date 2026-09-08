package com.trackmywealth.backend.repository;

import com.trackmywealth.backend.entity.AppUser;
import com.trackmywealth.backend.security.AppUserAuthSnapshot;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface AppUserRepository extends JpaRepository<AppUser, UUID> {

  // A derived `email = ?` query is NOT case-insensitive here despite the column being citext:
  // JDBC binds the parameter as `text`, and Postgres's operator resolution then picks the exact
  // `text = text` match (silently casting the citext column down to text) over the citext
  // equality operator - confirmed against a real Postgres 16 instance. Casting the parameter to
  // citext explicitly forces the case-insensitive comparison the column type promises.
  @Query(
      value = "SELECT EXISTS(SELECT 1 FROM app_user WHERE email = CAST(:email AS citext))",
      nativeQuery = true)
  boolean existsByEmail(@Param("email") String email);

  // US-02-02 login: same citext/JDBC parameter-binding caveat as existsByEmail above applies
  // here too - the explicit cast is what actually makes this case-insensitive.
  @Query(value = "SELECT * FROM app_user WHERE email = CAST(:email AS citext)", nativeQuery = true)
  Optional<AppUser> findByEmail(@Param("email") String email);

  // US-02-03: joined to the one user_session named by the presented token's sessionId claim (not
  // to every session the user has) so JwtAuthenticationFilter can check that specific session is
  // still ACTIVE in the same single query - a LEFT JOIN, since a since-deleted session must
  // degrade to sessionStatus == null (treated as "not authenticated"), not a query failure.
  @Query(
      "SELECT new com.trackmywealth.backend.security.AppUserAuthSnapshot("
          + "u.id, u.role, u.status, u.tokenVersion, h.id, s.status) "
          + "FROM AppUser u LEFT JOIN u.householdMember hm LEFT JOIN hm.household h "
          + "LEFT JOIN UserSession s ON s.user = u AND s.id = :sessionId "
          + "WHERE u.id = :userId")
  Optional<AppUserAuthSnapshot> findAuthSnapshot(
      @Param("userId") UUID userId, @Param("sessionId") UUID sessionId);

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

  // US-02-02: an atomic increment, not a read-modify-write via save() - AppUser carries a real
  // @Version column, and two concurrent wrong-password attempts loading the same row would
  // otherwise have one lose an ObjectOptimisticLockingFailureException (uncaught, an unhandled
  // 500) instead of both attempts correctly counting toward the lockout. failedLoginCount is
  // referenced twice in this SET clause - standard SQL UPDATE semantics evaluate every SET
  // expression against the row's pre-update value, so both reads see the same (correct) number.
  @Modifying
  @Query(
      "UPDATE AppUser u SET u.failedLoginCount = u.failedLoginCount + 1, "
          + "u.lockedUntil = CASE WHEN u.failedLoginCount + 1 >= :lockoutThreshold "
          + "THEN :lockedUntil ELSE u.lockedUntil END "
          + "WHERE u.id = :userId")
  void registerFailedLoginAttempt(
      @Param("userId") UUID userId,
      @Param("lockoutThreshold") int lockoutThreshold,
      @Param("lockedUntil") OffsetDateTime lockedUntil);

  // Same reasoning as registerFailedLoginAttempt above - an atomic reset rather than a
  // save() that a concurrent login (e.g. two devices at once) could lose to a version conflict.
  @Modifying
  @Query(
      "UPDATE AppUser u SET u.failedLoginCount = 0, u.lockedUntil = null, u.lastLoginAt = :loginAt"
          + " WHERE u.id = :userId")
  void registerSuccessfulLogin(
      @Param("userId") UUID userId, @Param("loginAt") OffsetDateTime loginAt);
}
