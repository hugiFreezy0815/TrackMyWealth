package com.trackmywealth.backend.repository;

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
  // the loaded entity, for the same reason as RefreshTokenRepository.revokeById - avoids clobbering
  // any other column (e.g. a concurrent rotation's refreshToken repoint) with a stale in-memory
  // copy. COALESCE preserves an existing revokedAt rather than overwriting it.
  @Modifying
  @Query(
      "UPDATE UserSession s SET s.status = 'REVOKED', s.revokedAt = COALESCE(s.revokedAt, :revokedAt)"
          + " WHERE s.id = :id")
  int revokeById(@Param("id") UUID id, @Param("revokedAt") OffsetDateTime revokedAt);
}
