package com.trackmywealth.backend.service;

import com.trackmywealth.backend.config.JwtProperties;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.JwtParser;
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
 * <p>It shares {@link JwtService}'s deployment signing key, but carries and requires the explicit
 * signed {@code mfa-challenge} token type (#200) as well as its purpose. This makes token-kind
 * separation a parser contract rather than an accidental consequence of which claims happen to be
 * absent today.
 */
@Service
public class MfaChallengeTokenService {

  private static final String PURPOSE_CLAIM = "purpose";
  private static final String CHALLENGE_PURPOSE = "mfa_challenge";
  private static final int TTL_MINUTES = 5;

  private final String issuer;
  private final SecretKey signingKey;
  private final JwtParser challengeTokenParser;

  public MfaChallengeTokenService(JwtProperties properties) {
    this.issuer = properties.issuer();
    this.signingKey = Keys.hmacShaKeyFor(properties.secret().getBytes(StandardCharsets.UTF_8));
    this.challengeTokenParser =
        Jwts.parser()
            .requireIssuer(issuer)
            .require(
                JwtTokenContract.TOKEN_TYPE_CLAIM, JwtTokenContract.MFA_CHALLENGE_TOKEN_TYPE)
            .require(PURPOSE_CLAIM, CHALLENGE_PURPOSE)
            .verifyWith(signingKey)
            .build();
  }

  public String issue(UUID userId) {
    Instant now = Instant.now();
    return Jwts.builder()
        .subject(userId.toString())
        .claim(JwtTokenContract.TOKEN_TYPE_CLAIM, JwtTokenContract.MFA_CHALLENGE_TOKEN_TYPE)
        .claim(PURPOSE_CLAIM, CHALLENGE_PURPOSE)
        .issuer(issuer)
        .issuedAt(Date.from(now))
        .expiration(Date.from(now.plus(TTL_MINUTES, ChronoUnit.MINUTES)))
        .signWith(signingKey)
        .compact();
  }

  /**
   * Empty if the token is malformed, expired, signed with a different key, issued by a different
   * issuer, has the wrong token type, lacks the MFA purpose/subject, or otherwise violates the
   * challenge-token contract - never throws.
   */
  public Optional<UUID> parse(String token) {
    try {
      Claims claims = challengeTokenParser.parseSignedClaims(token).getPayload();
      String subject = claims.getSubject();
      return subject == null ? Optional.empty() : Optional.of(UUID.fromString(subject));
    } catch (JwtException | IllegalArgumentException e) {
      return Optional.empty();
    }
  }
}
