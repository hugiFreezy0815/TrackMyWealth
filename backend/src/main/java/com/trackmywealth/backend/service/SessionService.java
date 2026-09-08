package com.trackmywealth.backend.service;

import com.trackmywealth.backend.dto.SessionSummaryResponse;
import com.trackmywealth.backend.entity.UserSession;
import com.trackmywealth.backend.repository.RefreshTokenRepository;
import com.trackmywealth.backend.repository.UserSessionRepository;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * US-02-03: list and revoke the caller's own {@code user_session} rows. Every lookup here is scoped
 * to the caller's own {@code userId} in the query itself (FR-TEN-006/FR-AUT-002: a session
 * belonging to someone else must look exactly like a nonexistent one, never a distinguishable 403).
 */
@Service
public class SessionService {

  private static final String ACTIVE = "ACTIVE";

  private final UserSessionRepository userSessionRepository;
  private final RefreshTokenRepository refreshTokenRepository;

  public SessionService(
      UserSessionRepository userSessionRepository, RefreshTokenRepository refreshTokenRepository) {
    this.userSessionRepository = userSessionRepository;
    this.refreshTokenRepository = refreshTokenRepository;
  }

  public List<SessionSummaryResponse> listSessions(UUID callerUserId, UUID callerSessionId) {
    return userSessionRepository
        .findByUser_IdAndStatusOrderByLastSeenAtDesc(callerUserId, ACTIVE)
        .stream()
        .map(session -> toSummary(session, callerSessionId))
        .toList();
  }

  /**
   * Revoking the session making this very request is expected and must succeed (US-02-03's own edge
   * case): the write below only affects requests still to come, since {@link
   * com.trackmywealth.backend.security.JwtAuthenticationFilter} already populated this request's
   * {@code SecurityContext} before this method ever runs.
   */
  @Transactional
  public SessionSummaryResponse revokeSession(
      UUID sessionId, UUID callerUserId, UUID callerSessionId) {
    UserSession session =
        userSessionRepository
            .findByIdAndUser_Id(sessionId, callerUserId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));

    OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
    // Revokes whichever refresh token the session CURRENTLY points at (read inside the same
    // statement), not session.getRefreshToken().getId() captured before this method's own
    // updates - a concurrent /auth/refresh rotating this same session between the load above and
    // here would otherwise leave the token it just issued un-revoked while this call kills only
    // the now-superseded one, so the session would show REVOKED while its refresh-token family
    // kept working.
    //
    // Deliberately revokes the refresh_token row BEFORE the user_session row - the same order
    // TokenRotationService.rotate() locks them in (its refresh_token update, then its
    // user_session repoint). A concurrent rotate() and revoke() on the same session otherwise
    // lock these two rows in opposite order and deadlock (Postgres aborts one side with an
    // unhandled 500) - confirmed by reproducing the exact interleaving during review. Lock
    // ordering must stay consistent with rotate() if either method's statement order ever
    // changes again.
    refreshTokenRepository.revokeCurrentTokenForSession(session.getId(), now);
    userSessionRepository.revokeById(session.getId(), now);

    // Not persisted - session is never save()'d again in this method - just reused to build the
    // response without a second, near-identical toSummary() overload.
    session.setStatus("REVOKED");
    return toSummary(session, callerSessionId);
  }

  private SessionSummaryResponse toSummary(UserSession session, UUID callerSessionId) {
    return new SessionSummaryResponse(
        session.getId(),
        session.getDeviceLabel(),
        session.getStatus(),
        session.getCreatedAt(),
        session.getLastSeenAt(),
        session.getId().equals(callerSessionId));
  }
}
