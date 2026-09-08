package com.trackmywealth.backend.repository;

import com.trackmywealth.backend.entity.UserSession;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface UserSessionRepository extends JpaRepository<UserSession, UUID> {

  // US-02-02 refresh rotation: the session created at login tracks whichever refresh token is
  // currently valid for it, so this is how TokenRotationService finds the session to re-point at
  // the newly rotated-in token.
  Optional<UserSession> findByRefreshToken_Id(UUID refreshTokenId);
}
