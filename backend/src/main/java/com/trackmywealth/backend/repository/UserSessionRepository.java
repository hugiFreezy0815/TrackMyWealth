package com.trackmywealth.backend.repository;

import com.trackmywealth.backend.entity.UserSession;
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
}
