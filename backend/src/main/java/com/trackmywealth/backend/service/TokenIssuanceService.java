package com.trackmywealth.backend.service;

import com.trackmywealth.backend.config.JwtProperties;
import com.trackmywealth.backend.dto.AuthTokensResponse;
import com.trackmywealth.backend.entity.AppUser;
import com.trackmywealth.backend.entity.RefreshToken;
import com.trackmywealth.backend.entity.UserSession;
import com.trackmywealth.backend.repository.RefreshTokenRepository;
import com.trackmywealth.backend.repository.UserSessionRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Issues a full access+refresh token pair and records the {@code user_session} it belongs to -
 * shared by any flow that logs a user in (the setup flow's auto-login today, {@code POST /login}
 * once US-02-02 exists). Deliberately does not implement rotation or family-reuse detection: this
 * story only ever issues a brand-new family's first token, never rotates one - that logic belongs
 * to US-02-02.
 */
@Service
public class TokenIssuanceService {

  private final JwtService jwtService;
  private final JwtProperties jwtProperties;
  private final RefreshTokenRepository refreshTokenRepository;
  private final UserSessionRepository userSessionRepository;
  private final SecureRandom secureRandom = new SecureRandom();

  public TokenIssuanceService(
      JwtService jwtService,
      JwtProperties jwtProperties,
      RefreshTokenRepository refreshTokenRepository,
      UserSessionRepository userSessionRepository) {
    this.jwtService = jwtService;
    this.jwtProperties = jwtProperties;
    this.refreshTokenRepository = refreshTokenRepository;
    this.userSessionRepository = userSessionRepository;
  }

  /**
   * @param rawIpAddress the caller's address, as reported by the servlet container - never
   *     persisted as-is; only its hash is stored (NFR-OPS-005). May be {@code null}.
   */
  public AuthTokensResponse issueTokens(AppUser user, String deviceLabel, String rawIpAddress) {
    String plaintextRefreshToken = generateOpaqueToken();
    OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);

    RefreshToken refreshToken = new RefreshToken();
    refreshToken.setUser(user);
    // A brand-new login always starts its own rotation family (FR-AUT-004) - nothing to rotate
    // away from yet.
    refreshToken.setFamilyId(UUID.randomUUID());
    refreshToken.setTokenHash(hash(plaintextRefreshToken));
    refreshToken.setDeviceLabel(deviceLabel);
    refreshToken.setExpiresAt(now.plus(jwtProperties.refreshTokenTtlDays(), ChronoUnit.DAYS));
    refreshToken = refreshTokenRepository.save(refreshToken);

    UserSession session = new UserSession();
    session.setUser(user);
    session.setRefreshToken(refreshToken);
    session.setDeviceLabel(deviceLabel);
    session.setIpAddressHash(rawIpAddress == null ? null : hash(rawIpAddress));
    session.setLastSeenAt(now);
    userSessionRepository.save(session);

    String accessToken = jwtService.issueAccessToken(user.getId());
    return new AuthTokensResponse(
        accessToken, plaintextRefreshToken, "Bearer", jwtService.accessTokenTtlSeconds());
  }

  private String generateOpaqueToken() {
    byte[] bytes = new byte[32];
    secureRandom.nextBytes(bytes);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }

  // Refresh tokens are high-entropy (256 random bits) already, so a fast cryptographic hash is
  // the right tool here - unlike a user-chosen password, brute-forcing the hash preimage is
  // infeasible regardless of hash speed. Argon2id (see PasswordEncoder) is reserved for
  // human-chosen secrets.
  private String hash(String value) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      byte[] hashed = digest.digest(value.getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(hashed);
    } catch (NoSuchAlgorithmException e) {
      // SHA-256 is a JVM-mandatory algorithm (JLS/JCA baseline) - this cannot happen on any
      // conforming JVM.
      throw new IllegalStateException("SHA-256 unavailable", e);
    }
  }
}
