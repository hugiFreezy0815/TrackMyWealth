package com.trackmywealth.backend.repository;

import com.trackmywealth.backend.entity.AppUser;
import com.trackmywealth.backend.security.AppUserAuthSnapshot;
import jakarta.persistence.LockModeType;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface AppUserRepository extends JpaRepository<AppUser, UUID> {

  // Named once and reused across every @Param(USER_ID) below - PMD's AvoidDuplicateLiterals
  // flags the same string literal appearing 4+ times in one file.
  String USER_ID = "userId";

  // A derived `email = ?` query is NOT case-insensitive here despite the column being citext:
  // JDBC binds the parameter as `text`, and Postgres's operator resolution then picks the exact
  // `text = text` match (silently casting the citext column down to text) over the citext
  // equality operator - confirmed against a real Postgres 16 instance. Casting the parameter to
  // citext explicitly forces the case-insensitive comparison the column type promises.
  @Query(
      value = "SELECT EXISTS(SELECT 1 FROM app_user WHERE email = CAST(:email AS citext))",
      nativeQuery = true)
  boolean existsByEmail(@Param("email") String email);

  // WorkspaceMemberService's "who is making this request" lookup - a single query against the
  // FK column itself, not app_user's full column set followed by a lazy-association round trip
  // (findById(...).map(AppUser::getWorkspaceMember) triggers exactly that second query).
  @Query("SELECT u.workspaceMember.id FROM AppUser u WHERE u.id = :userId")
  Optional<UUID> findWorkspaceMemberId(@Param(USER_ID) UUID userId);

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
          + "u.id, u.role, u.status, u.tokenVersion, w.id, wm.status, s.status) "
          + "FROM AppUser u LEFT JOIN u.workspaceMember wm LEFT JOIN wm.workspace w "
          + "LEFT JOIN UserSession s ON s.user = u AND s.id = :sessionId "
          + "WHERE u.id = :userId")
  Optional<AppUserAuthSnapshot> findAuthSnapshot(
      @Param(USER_ID) UUID userId, @Param("sessionId") UUID sessionId);

  // US-02-01's last-active-administrator guard (FR-USR-005): a row-level lock over every
  // currently active administrator, not a DB constraint - see the comment on this rule in V2 and
  // in the story itself. Must be called inside the same @Transactional method that then performs
  // the disable/demote, so the lock is held until that method's own commit. PostgreSQL rejects
  // FOR UPDATE directly on an aggregate query ("FOR UPDATE is not allowed with aggregate
  // functions") - the inner subquery locks the actual rows, the outer aggregate then just counts
  // the (already-locked) result. ORDER BY id on the inner subquery: this and {@code
  // lockTargetAndActiveAdministrators} below both lock overlapping sets of these same rows from
  // different call sites (editUser's demote path calls this one directly; disableUser calls the
  // other) - without both acquiring them in the same deterministic order, two concurrent callers
  // could each lock a different row in this set first and then deadlock reaching for the other's
  // (see #62's follow-up finding: id is a random UUID, not sequential, so relying on the
  // planner's unordered scan order to happen to agree between two different query texts is not
  // safe).
  @Query(
      value =
          "SELECT count(*) FROM (SELECT id FROM app_user WHERE role = 'SYSTEM_ADMINISTRATOR' AND"
              + " status = 'ACTIVE' ORDER BY id FOR UPDATE) locked_active_administrators",
      nativeQuery = true)
  long countActiveAdministratorsForUpdate();

  // #62 follow-up: disableUser() must lock its target row AND (if applicable) every other
  // currently active administrator in ONE statement, not two separate ones - an earlier version
  // of this fix locked the target first via a standalone findByIdForUpdate, then conditionally
  // took countActiveAdministratorsForUpdate's broader lock; two concurrent disableUser() calls on
  // *different* active-administrator targets could then each hold their own target's lock first
  // and deadlock reaching for the other's (confirmed empirically). ORDER BY id gives this query
  // the same deterministic acquisition order as countActiveAdministratorsForUpdate above for
  // whatever rows the two queries both end up matching, which is what actually rules a cycle out
  // - not merely that this is "one query" per call.
  //
  // The second OR-branch is deliberately gated by the EXISTS check, not just "role/status =
  // active" unconditionally: an earlier version of this query always matched every active
  // administrator regardless of the target, which correctly avoided the two-step deadlock above
  // but introduced a *different* one - every disableUser() call, even for an ordinary
  // STANDARD_USER target, ended up locking every unrelated active administrator too, so any
  // admin account frequently used as an acting administrator elsewhere became an unintended
  // point of contention between otherwise-unrelated disableUser() calls (confirmed empirically:
  // enough concurrent disableUser() calls sharing one such actor deadlocked on it). Gating the
  // second branch on the target itself currently being an active administrator - evaluated in
  // the same atomic statement, not a separate preceding read - means a non-administrator target
  // locks only itself, while an administrator target still gets the full protection, with no
  // race window between "check the role" and "take the lock."
  @Query(
      value =
          "SELECT * FROM app_user WHERE id = :targetId OR ("
              + "role = 'SYSTEM_ADMINISTRATOR' AND status = 'ACTIVE' AND EXISTS ("
              + "SELECT 1 FROM app_user t WHERE t.id = :targetId "
              + "AND t.role = 'SYSTEM_ADMINISTRATOR' AND t.status = 'ACTIVE')"
              + ") ORDER BY id FOR UPDATE",
      nativeQuery = true)
  List<AppUser> lockTargetAndActiveAdministrators(@Param("targetId") UUID targetId);

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
      @Param(USER_ID) UUID userId,
      @Param("lockoutThreshold") int lockoutThreshold,
      @Param("lockedUntil") OffsetDateTime lockedUntil);

  // Same reasoning as registerFailedLoginAttempt above - an atomic reset rather than a
  // save() that a concurrent login (e.g. two devices at once) could lose to a version conflict.
  @Modifying
  @Query(
      "UPDATE AppUser u SET u.failedLoginCount = 0, u.lockedUntil = null, u.lastLoginAt = :loginAt"
          + " WHERE u.id = :userId")
  void registerSuccessfulLogin(
      @Param(USER_ID) UUID userId, @Param("loginAt") OffsetDateTime loginAt);

  // US-02-04: atomically "reserves" one attempt at a guessable credential (a TOTP code, or the
  // password re-entered to enroll/disable MFA) BEFORE it is checked, instead of checking first and
  // counting a failure afterwards. Check-then-count lets N parallel guesses all pass the lockout
  // check before any of them has been counted; here the WHERE clause is re-evaluated by Postgres
  // against the freshest committed row once each concurrent UPDATE gets the row lock, so at most
  // `lockoutThreshold` attempts are ever let through per lockout window. Returns 0 rows when the
  // account is currently locked. The caller resets the counter on success
  // (clearFailedLoginAttempts / registerSuccessfulLogin); a failure needs no further write, having
  // already been counted.
  @Modifying
  @Query(
      "UPDATE AppUser u SET u.failedLoginCount = u.failedLoginCount + 1, "
          + "u.lockedUntil = CASE WHEN u.failedLoginCount + 1 >= :lockoutThreshold "
          + "THEN :lockedUntil ELSE u.lockedUntil END "
          + "WHERE u.id = :userId AND (u.lockedUntil IS NULL OR u.lockedUntil <= :now)")
  int reserveLoginAttempt(
      @Param(USER_ID) UUID userId,
      @Param("lockoutThreshold") int lockoutThreshold,
      @Param("lockedUntil") OffsetDateTime lockedUntil,
      @Param("now") OffsetDateTime now);

  // Resets the failure budget without touching last_login_at - for a credential proven outside a
  // login (re-authentication for MFA enroll/disable, confirming an enrollment).
  @Modifying
  @Query("UPDATE AppUser u SET u.failedLoginCount = 0, u.lockedUntil = null WHERE u.id = :userId")
  void clearFailedLoginAttempts(@Param(USER_ID) UUID userId);

  // US-02-04: the MFA writes below are targeted UPDATEs of just the MFA columns, never a save() of
  // the whole entity - a save() writes every column from a snapshot loaded earlier in the
  // request, and the login-state bulk updates above bump the row version (trigger
  // app_user_bump_version), so a concurrent login would turn it into an
  // ObjectOptimisticLockingFailureException (a 500).
  //
  // Native queries because mfa_last_used_step (V29) is deliberately not mapped on AppUser: an
  // unmapped column can never be overwritten with a stale value by some other flow's save().

  // Stores the freshly generated (already encrypted) secret and starts a new code stream (a new
  // secret invalidates the old one's last-used step). Conditional on MFA not being enabled, in
  // the statement itself, so enrolling can never replace a live secret even if a confirm raced
  // it. Returns 0 rows if MFA is enabled.
  @Modifying
  @Query(
      value =
          "UPDATE app_user SET mfa_totp_secret = :secret, mfa_last_used_step = NULL "
              + "WHERE id = :userId AND mfa_enabled = false",
      nativeQuery = true)
  int startMfaEnrollment(@Param(USER_ID) UUID userId, @Param("secret") String encryptedSecret);

  @Modifying
  @Query(
      "UPDATE AppUser u SET u.mfaEnabled = true "
          + "WHERE u.id = :userId AND u.mfaTotpSecret IS NOT NULL")
  int confirmMfaEnrollment(@Param(USER_ID) UUID userId);

  // TOTP replay protection (RFC 6238 section 5.2): accepts a time step only if it is strictly
  // newer than the last accepted one, in a single conditional UPDATE so two concurrent
  // submissions of the same code cannot both succeed. Returns 0 rows for a replayed (or older)
  // step.
  @Modifying
  @Query(
      value =
          "UPDATE app_user SET mfa_last_used_step = :step WHERE id = :userId "
              + "AND (mfa_last_used_step IS NULL OR mfa_last_used_step < :step)",
      nativeQuery = true)
  int markMfaStepUsed(@Param(USER_ID) UUID userId, @Param("step") long step);

  // Disabling MFA discards the secret, the flag and the replay state in one atomic statement.
  @Modifying
  @Query(
      value =
          "UPDATE app_user SET mfa_totp_secret = NULL, mfa_enabled = false, "
              + "mfa_last_used_step = NULL WHERE id = :userId",
      nativeQuery = true)
  void clearMfa(@Param(USER_ID) UUID userId);

  // #62: reactivateUser() mutates this row and then mutates user_session/refresh_token (for the
  // same user) in an order forced by a real FK constraint. Racing it against disableUser() on
  // the *same* target user could otherwise deadlock, not on user_session/refresh_token directly
  // but on this row itself (confirmed against a real Postgres instance: several concurrent
  // optimistic-locked UPDATEs to one row can produce a genuine wait-for cycle via Postgres's
  // tuple-lock queueing, not just a plain serialize-and-wait). Locking this row FIRST, before
  // reactivateUser() does anything else, fully serializes it against any concurrent call
  // touching the same target - the other can't even read the row until this one commits.
  //
  // reactivateUser() never needs the broader active-administrator lock disableUser() sometimes
  // does (reactivating can never drop the active-administrator count), so a single-row lock here
  // is safe on its own - unlike disableUser(), which must go through {@code
  // lockTargetAndActiveAdministrators} instead precisely because pairing this method with that
  // broader lock as two separate statements is what caused #62's follow-up deadlock.
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("SELECT u FROM AppUser u WHERE u.id = :id")
  Optional<AppUser> findByIdForUpdate(@Param("id") UUID id);
}
