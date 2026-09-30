package com.trackmywealth.backend.repository;

import static com.trackmywealth.backend.repository.RefreshTokenRepository.REVOKED_AT_PARAM;

import com.trackmywealth.backend.entity.RefreshToken;
import com.trackmywealth.backend.entity.UserSession;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface UserSessionRepository extends JpaRepository<UserSession, UUID> {

  // US-02-02 refresh rotation: the session created at login tracks whichever refresh token is
  // currently valid for it, so this is how TokenRotationService finds the session to re-point at
  // the newly rotated-in token.
  Optional<UserSession> findByRefreshToken_Id(UUID refreshTokenId);

  // US-02-02 refresh rotation: a targeted bulk update, not a read-then-save() of the loaded
  // entity - UserSession has no @Version, so a plain save() would write back every mapped column
  // from whatever was in memory when it was loaded, silently clobbering `status`/`revokedAt` if a
  // concurrent US-02-03 session revoke had already set them in the meantime (confirmed: this was
  // the exact bug before this fix - a refresh racing a revoke of the same session could
  // resurrect it to ACTIVE).
  //
  // #190: only an ACTIVE session is ever repointed. Theft detection's family-wide token update
  // cannot see a token a concurrent legitimate rotation inserts but has not yet committed, so that
  // new token can survive the sweep while its session is revoked right after. 0 rows here is how
  // TokenRotationService recognises such a token on its next use and kills it instead of rotating.
  @Modifying
  @Query(
      "UPDATE UserSession s SET s.refreshToken = :refreshToken, s.lastSeenAt = :lastSeenAt "
          + "WHERE s.id = :id AND s.status = 'ACTIVE'")
  int repointRefreshToken(
      @Param("id") UUID id,
      @Param("refreshToken") RefreshToken refreshToken,
      @Param("lastSeenAt") OffsetDateTime lastSeenAt);

  // US-02-01 reactivate: a prerequisite for RefreshTokenRepository.deleteRevokedTokensForUser -
  // user_session.refresh_token_id has its own FK to refresh_token, so a still-referenced revoked
  // token cannot be deleted until the session pointing at it is detached first. Only ever touches
  // a session whose *current* token is already revoked, never a live one.
  @Modifying
  @Query(
      "UPDATE UserSession s SET s.refreshToken = null, s.status = 'REVOKED' "
          + "WHERE s.user.id = :userId AND s.refreshToken.revokedAt IS NOT NULL")
  int detachRevokedRefreshTokensForUser(@Param("userId") UUID userId);

  // US-02-03: "my active sessions", most recently used first.
  List<UserSession> findByUser_IdAndStatusOrderByLastSeenAtDesc(UUID userId, String status);

  // US-02-03: scoped to the caller's own id in the query itself, not just checked afterward - a
  // session belonging to someone else looks exactly like a nonexistent one to the caller
  // (FR-TEN-006:
  // an unauthorized-vs-nonexistent request must be indistinguishable), so SessionService can turn
  // an
  // empty Optional straight into a 404 either way.
  Optional<UserSession> findByIdAndUser_Id(UUID id, UUID userId);

  // US-02-03 revoke: a targeted, single-column-pair bulk update rather than a read-then-save() of
  // the loaded entity - same reasoning as repointRefreshToken above. COALESCE preserves an
  // existing revokedAt rather than overwriting it.
  @Modifying
  @Query(
      "UPDATE UserSession s SET s.status = 'REVOKED', s.revokedAt = COALESCE(s.revokedAt, :revokedAt)"
          + " WHERE s.id = :id")
  int revokeById(@Param("id") UUID id, @Param(REVOKED_AT_PARAM) OffsetDateTime revokedAt);

  // FR-AUT-004 / #190: refresh-token reuse invalidates the entire token-rotation family and
  // must immediately invalidate access tokens already issued for its session. The session points
  // only at the family's CURRENT refresh token, so matching by refreshToken.familyId (rather than
  // by the reused, already-rotated-away token id) still finds it after successful rotations.
  //
  // Deliberately a targeted bulk update, for the same reason as revokeById above: callers may hold
  // a managed UserSession loaded before a concurrent security event, and a full entity save could
  // write stale status/revocation state back over this update.
  @Modifying
  @Query(
      value =
          "UPDATE user_session SET status = 'REVOKED', "
              + "revoked_at = COALESCE(revoked_at, :revokedAt) "
              + "WHERE status = 'ACTIVE' AND refresh_token_id IN "
              + "(SELECT id FROM refresh_token WHERE family_id = :refreshTokenFamilyId)",
      nativeQuery = true)
  int revokeActiveSessionsByRefreshTokenFamilyId(
      @Param("refreshTokenFamilyId") UUID refreshTokenFamilyId,
      @Param(REVOKED_AT_PARAM) OffsetDateTime revokedAt);

  // US-02-01 disable: user_session.status is now a security-relevant signal (US-02-03's
  // JwtAuthenticationFilter check), not just a display field, so disabling a user must mark their
  // sessions REVOKED too - previously only their refresh tokens were revoked
  // (revokeAllActiveTokensForUser), leaving stale ACTIVE session rows a disabled user could no
  // longer actually use (AppUser.status is checked first) but that any future feature trusting
  // user_session.status as ground truth (a session-audit view, an expiry job) would see wrongly.
  @Modifying
  @Query(
      "UPDATE UserSession s SET s.status = 'REVOKED', s.revokedAt = :revokedAt "
          + "WHERE s.user.id = :userId AND s.status = 'ACTIVE'")
  int revokeAllActiveSessionsForUser(
      @Param("userId") UUID userId, @Param(REVOKED_AT_PARAM) OffsetDateTime revokedAt);
}
