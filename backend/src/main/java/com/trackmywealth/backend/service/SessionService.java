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

    // Deliberately NOT session.setStatus("REVOKED") on the entity: `session` is still a
    // JPA-managed entity (loaded via the repository within this open transaction, never
    // detached), and the two bulk updates above bypass the persistence context entirely - they
    // don't touch its in-memory field values or its dirty-checking snapshot. Mutating a mapped
    // field here would make Hibernate's implicit pre-commit flush dirty-check it and reissue a
    // full-column UPDATE from the entity's STALE in-memory state (still revokedAt=null,
    // refreshToken=<pre-revoke value> from the original load) - clobbering the bulk updates'
    // effect right back, and under the exact concurrent rotate()-vs-revoke() race this fix exists
    // for, reverting a concurrent rotation's refreshToken repoint too. Confirmed empirically: an
    // earlier version of this method did exactly that, and revoked_at came back NULL in the
    // database despite status='REVOKED'. Passing the status in explicitly, rather than mutating
    // the entity, is what avoids re-triggering that.
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
