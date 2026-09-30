package com.trackmywealth.backend.service;

import com.trackmywealth.backend.config.JwtProperties;
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
 * Issues and validates the short-lived, single-purpose token that bridges US-02-04's two-step
 * login: once step 1 (password) succeeds for an MFA-enabled user, this - never an access or refresh
 * token - is all {@link LoginService} hands back, so the client doesn't need to resend the password
 * for step 2 ({@code POST /api/v1/auth/mfa/verify}).
 *
 * <p>Signed with the same key as {@link JwtService} (deployment configuration, not a second secret
 * to manage) but shaped so the two kinds of token can never be mistaken for one another: this one
 * carries a {@code purpose} claim {@link JwtService} never sets, and omits the {@code
 * tokenVersion}/{@code sessionId} claims {@link JwtService#parseAccessToken} requires - so a
 * challenge token can never pass {@link JwtService#parseAccessToken} even if presented as a bearer
 * token, and a real access token can never pass {@link #parse} either.
 */
@Service
public class MfaChallengeTokenService {

  private static final String PURPOSE_CLAIM = "purpose";
  private static final String CHALLENGE_PURPOSE = "mfa_challenge";
  private static final int TTL_MINUTES = 5;

  private final JwtProperties properties;
  private final SecretKey signingKey;

  public MfaChallengeTokenService(JwtProperties properties) {
    this.properties = properties;
    this.signingKey = Keys.hmacShaKeyFor(properties.secret().getBytes(StandardCharsets.UTF_8));
  }

  public String issue(UUID userId) {
    Instant now = Instant.now();
    return Jwts.builder()
        .subject(userId.toString())
        .claim(PURPOSE_CLAIM, CHALLENGE_PURPOSE)
        .issuer(properties.issuer())
        .issuedAt(Date.from(now))
        .expiration(Date.from(now.plus(TTL_MINUTES, ChronoUnit.MINUTES)))
        .signWith(signingKey)
        .compact();
  }

  /**
   * Empty if the token is malformed, expired, signed with a different key, issued by a different
   * issuer (#189, the same contract as {@link JwtService#parseAccessToken}), without a subject, or
   * not actually an MFA-challenge token (e.g. a real access token presented here instead) - never
   * throws.
   */
  public Optional<UUID> parse(String token) {
    try {
      Claims claims =
          Jwts.parser()
              .requireIssuer(properties.issuer())
              .verifyWith(signingKey)
              .build()
              .parseSignedClaims(token)
              .getPayload();
      String subject = claims.getSubject();
      if (subject == null || !CHALLENGE_PURPOSE.equals(claims.get(PURPOSE_CLAIM, String.class))) {
        return Optional.empty();
      }
      return Optional.of(UUID.fromString(subject));
    } catch (JwtException | IllegalArgumentException e) {
      return Optional.empty();
    }
  }
}
