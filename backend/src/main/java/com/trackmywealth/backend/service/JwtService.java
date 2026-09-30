package com.trackmywealth.backend.service;

import com.trackmywealth.backend.config.JwtProperties;
import com.trackmywealth.backend.security.AccessTokenClaims;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.Optional;
import java.util.UUID;
import javax.crypto.SecretKey;
import org.springframework.stereotype.Service;

/**
 * Issues and validates short-lived JWT access tokens (FR-AUT-003). Refresh tokens are a separate,
 * opaque, server-side concept ({@code refresh_token} table, see {@link TokenIssuanceService}) -
 * this class only ever handles the self-contained, short-lived half of the pair.
 */
@Service
public class JwtService {

  private static final String TOKEN_VERSION_CLAIM = "tokenVersion";
  private static final String SESSION_ID_CLAIM = "sessionId";

  private final JwtProperties properties;
  private final SecretKey signingKey;

  public JwtService(JwtProperties properties) {
    this.properties = properties;
    this.signingKey = Keys.hmacShaKeyFor(properties.secret().getBytes(StandardCharsets.UTF_8));
  }

  /** Access-token TTL, exposed so callers can report {@code expiresInSeconds} alongside it. */
  public long accessTokenTtlSeconds() {
    return properties.accessTokenTtlMinutes() * 60L;
  }

  /**
   * @param tokenVersion the issuing user's {@code app_user.token_version} at issuance time -
   *     embedded so {@link #parseAccessToken} can detect a since-revoked token (FR-AUT-005:
   *     incrementing token_version must invalidate every previously issued access token
   *     immediately, not wait for JWT expiry).
   * @param sessionId the {@code user_session} this token belongs to (US-02-03) - embedded so a
   *     single session can be revoked without affecting a user's other active sessions, which
   *     {@code tokenVersion} alone (a per-user, not per-session, counter) cannot express.
   */
  public String issueAccessToken(UUID userId, int tokenVersion, UUID sessionId) {
    Instant now = Instant.now();
    return Jwts.builder()
        .subject(userId.toString())
        .claim(TOKEN_VERSION_CLAIM, tokenVersion)
        .claim(SESSION_ID_CLAIM, sessionId.toString())
        .issuer(properties.issuer())
        .issuedAt(Date.from(now))
        .expiration(Date.from(now.plus(properties.accessTokenTtlMinutes(), ChronoUnit.MINUTES)))
        .signWith(signingKey)
        .compact();
  }

  /**
   * Empty if the token is malformed, expired, signed with a different key, issued by a different
   * issuer, or missing/mis-typing any claim required by the TrackMyWealth access-token contract -
   * never throws.
   */
  public Optional<AccessTokenClaims> parseAccessToken(String token) {
    try {
      Claims claims =
          Jwts.parser()
              .requireIssuer(properties.issuer())
              .verifyWith(signingKey)
              .build()
              .parseSignedClaims(token)
              .getPayload();

      String subject = claims.getSubject();
      Date issuedAt = claims.getIssuedAt();
      Date expiration = claims.getExpiration();
      Integer tokenVersion = claims.get(TOKEN_VERSION_CLAIM, Integer.class);
      String sessionIdClaim = claims.get(SESSION_ID_CLAIM, String.class);

      if (subject == null
          || issuedAt == null
          || expiration == null
          || tokenVersion == null
          || sessionIdClaim == null) {
        return Optional.empty();
      }

      UUID userId = UUID.fromString(subject);
      UUID sessionId = UUID.fromString(sessionIdClaim);
      return Optional.of(new AccessTokenClaims(userId, tokenVersion, sessionId));
    } catch (JwtException | IllegalArgumentException e) {
      return Optional.empty();
    }
  }
}
