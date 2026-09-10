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
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Issues a full access+refresh token pair and records the {@code user_session} it belongs to -
 * shared by any flow that logs a user in (the setup flow's auto-login, {@link LoginService}).
 * Deliberately does not implement rotation or family-reuse detection: this always issues a
 * brand-new family's first token, never rotates one - that is {@link TokenRotationService}'s job.
 */
@Service
public class TokenIssuanceService {

  private final JwtService jwtService;
  private final JwtProperties jwtProperties;
  private final RefreshTokenRepository refreshTokenRepository;
  private final UserSessionRepository userSessionRepository;
  private final TokenHashingService tokenHashingService;

  public TokenIssuanceService(
      JwtService jwtService,
      JwtProperties jwtProperties,
      RefreshTokenRepository refreshTokenRepository,
      UserSessionRepository userSessionRepository,
      TokenHashingService tokenHashingService) {
    this.jwtService = jwtService;
    this.jwtProperties = jwtProperties;
    this.refreshTokenRepository = refreshTokenRepository;
    this.userSessionRepository = userSessionRepository;
    this.tokenHashingService = tokenHashingService;
  }

  /**
   * @param rawIpAddress the caller's address, as reported by the servlet container - never
   *     persisted as-is; only its hash is stored (NFR-OPS-005). May be {@code null}. As of #60,
   *     this is {@code getRemoteAddr()}, which {@code TRUSTED_PROXIES}/{@code RemoteIpValve} can
   *     rewrite from X-Forwarded-For when the direct peer is a configured trusted proxy - the same
   *     trust boundary {@code RateLimitFilter} documents for rate limiting applies here too:
   *     trusting a proxy that doesn't sanitize/overwrite the header lets a client forge the address
   *     hashed into this session.
   */
  public AuthTokensResponse issueTokens(AppUser user, String deviceLabel, String rawIpAddress) {
    String plaintextRefreshToken = tokenHashingService.generateOpaqueToken();
    OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);

    RefreshToken refreshToken = new RefreshToken();
    refreshToken.setUser(user);
    // A brand-new login always starts its own rotation family (FR-AUT-004) - nothing to rotate
    // away from yet.
    refreshToken.setFamilyId(UUID.randomUUID());
    refreshToken.setTokenHash(tokenHashingService.sha256Hex(plaintextRefreshToken));
    refreshToken.setDeviceLabel(deviceLabel);
    refreshToken.setExpiresAt(now.plus(jwtProperties.refreshTokenTtlDays(), ChronoUnit.DAYS));
    refreshToken = refreshTokenRepository.save(refreshToken);

    UserSession session = new UserSession();
    session.setUser(user);
    session.setRefreshToken(refreshToken);
    session.setDeviceLabel(deviceLabel);
    session.setIpAddressHash(
        rawIpAddress == null ? null : tokenHashingService.sha256Hex(rawIpAddress));
    session.setLastSeenAt(now);
    UserSession savedSession = userSessionRepository.save(session);

    String accessToken =
        jwtService.issueAccessToken(user.getId(), user.getTokenVersion(), savedSession.getId());
    return new AuthTokensResponse(
        accessToken, plaintextRefreshToken, "Bearer", jwtService.accessTokenTtlSeconds());
  }
}
