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
    userSessionRepository.revokeById(session.getId(), now);
    // Revokes whichever refresh token the session CURRENTLY points at (read inside the same
    // statement), not session.getRefreshToken().getId() captured before this method's own
    // updates - a concurrent /auth/refresh rotating this same session between the load above and
    // here would otherwise leave the token it just issued un-revoked while this call kills only
    // the now-superseded one, so the session would show REVOKED while its refresh-token family
    // kept working.
    refreshTokenRepository.revokeCurrentTokenForSession(session.getId(), now);

    return toSummary(session, "REVOKED", callerSessionId);
  }

  private SessionSummaryResponse toSummary(UserSession session, UUID callerSessionId) {
    return toSummary(session, session.getStatus(), callerSessionId);
  }

  private SessionSummaryResponse toSummary(
      UserSession session, String status, UUID callerSessionId) {
    return new SessionSummaryResponse(
        session.getId(),
        session.getDeviceLabel(),
        status,
        session.getCreatedAt(),
        session.getLastSeenAt(),
        session.getId().equals(callerSessionId));
  }
}
