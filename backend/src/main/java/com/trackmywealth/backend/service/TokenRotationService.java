package com.trackmywealth.backend.service;

import com.trackmywealth.backend.config.JwtProperties;
import com.trackmywealth.backend.dto.AuthTokensResponse;
import com.trackmywealth.backend.entity.AppUser;
import com.trackmywealth.backend.entity.RefreshToken;
import com.trackmywealth.backend.entity.UserSession;
import com.trackmywealth.backend.repository.RefreshTokenRepository;
import com.trackmywealth.backend.repository.UserSessionRepository;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * US-02-02: refresh-token rotation and reuse ("theft") detection (FR-AUT-004). Deliberately
 * separate from {@link TokenIssuanceService}, which only ever starts a brand-new rotation family -
 * this class is the one that continues an existing one, or kills it on reuse.
 */
@Service
public class TokenRotationService {

  private static final String ACTIVE = "ACTIVE";

  private final RefreshTokenRepository refreshTokenRepository;
  private final UserSessionRepository userSessionRepository;
  private final JwtService jwtService;
  private final JwtProperties jwtProperties;
  private final TokenHashingService tokenHashingService;

  public TokenRotationService(
      RefreshTokenRepository refreshTokenRepository,
      UserSessionRepository userSessionRepository,
      JwtService jwtService,
      JwtProperties jwtProperties,
      TokenHashingService tokenHashingService) {
    this.refreshTokenRepository = refreshTokenRepository;
    this.userSessionRepository = userSessionRepository;
    this.jwtService = jwtService;
    this.jwtProperties = jwtProperties;
    this.tokenHashingService = tokenHashingService;
  }

  // noRollbackFor: the theft-detection branch's whole point is to persist
  // markFamilyAsTheftSuspected's bulk update even though the method also throws to report the
  // reuse to the caller - Spring's default rollback-on-any-unchecked-exception would otherwise
  // silently discard that write, since ResponseStatusException is unchecked.
  @Transactional(noRollbackFor = ResponseStatusException.class)
  public AuthTokensResponse rotate(String plaintextRefreshToken) {
    OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
    RefreshToken current =
        refreshTokenRepository
            .findByTokenHash(tokenHashingService.sha256Hex(plaintextRefreshToken))
            .orElseThrow(this::invalidRefreshToken);

    AppUser user = current.getUser();
    if (!ACTIVE.equals(user.getStatus())) {
      // Covers a disabled/reactivated-elsewhere account without treating it as theft - US-02-01's
      // disable already revokes every active refresh token for the user, so this branch mainly
      // guards against that revocation racing this rotation, not a fresh attack.
      throw invalidRefreshToken();
    }

    if (current.getRevokedAt() != null) {
      // FR-AUT-004: this token was already rotated away - being presented again means either a
      // stale client retry after a lost response, or a stolen token racing the legitimate client.
      // Both are indistinguishable from here, so the safe response is the same either way: kill
      // every token in the family and force a fresh login.
      refreshTokenRepository.markFamilyAsTheftSuspected(current.getFamilyId(), now);
      throw refreshTokenAlreadyUsed();
    }

    if (current.getExpiresAt().isBefore(now)) {
      throw new ResponseStatusException(
          HttpStatus.UNAUTHORIZED, "Refresh token has expired. Please log in again.");
    }

    RefreshToken next = new RefreshToken();
    next.setUser(user);
    next.setFamilyId(current.getFamilyId());
    String plaintextNext = tokenHashingService.generateOpaqueToken();
    next.setTokenHash(tokenHashingService.sha256Hex(plaintextNext));
    next.setDeviceLabel(current.getDeviceLabel());
    next.setExpiresAt(now.plus(jwtProperties.refreshTokenTtlDays(), ChronoUnit.DAYS));
    RefreshToken savedNext = refreshTokenRepository.save(next);

    int rotated =
        refreshTokenRepository.markRotatedOutByIfStillActive(
            current.getId(), savedNext.getId(), now);
    if (rotated == 0) {
      // Lost a race: another transaction revoked `current` between the read above and this
      // conditional update (a concurrent rotate() of the same token, or a concurrent
      // theft-detection sweep). `savedNext` must not be left behind as an extra live token for
      // this family, and the safe response to "this exact race just happened" is the same as any
      // other reuse: kill the whole family.
      refreshTokenRepository.delete(savedNext);
      refreshTokenRepository.markFamilyAsTheftSuspected(current.getFamilyId(), now);
      throw refreshTokenAlreadyUsed();
    }

    // US-02-03: every access token is now bound to a session id, so - unlike before, when the
    // session lookup was best-effort (ifPresent) - a refresh token with no backing session is
    // treated as invalid rather than silently issuing an access token nothing can ever revoke
    // individually. In practice a session always exists here: TokenIssuanceService creates one for
    // every login, and this is the only place a session's refreshToken pointer ever moves.
    UserSession session =
        userSessionRepository
            .findByRefreshToken_Id(current.getId())
            .orElseThrow(this::invalidRefreshToken);
    // A targeted bulk update, not session.setRefreshToken(savedNext) + save() - UserSession has
    // no @Version, and US-02-03's revoke can concurrently flip this same row's status/revokedAt
    // via its own bulk update; a plain save() here would write back this method's in-memory
    // (pre-revoke) status, silently resurrecting a session someone just revoked.
    userSessionRepository.repointRefreshToken(session.getId(), savedNext, now);

    String accessToken =
        jwtService.issueAccessToken(user.getId(), user.getTokenVersion(), session.getId());
    return new AuthTokensResponse(
        accessToken, plaintextNext, "Bearer", jwtService.accessTokenTtlSeconds());
  }

  private ResponseStatusException invalidRefreshToken() {
    return new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid refresh token.");
  }

  private ResponseStatusException refreshTokenAlreadyUsed() {
    return new ResponseStatusException(
        HttpStatus.UNAUTHORIZED,
        "Refresh token has already been used. All sessions in this family have been revoked -"
            + " please log in again.");
  }
}
