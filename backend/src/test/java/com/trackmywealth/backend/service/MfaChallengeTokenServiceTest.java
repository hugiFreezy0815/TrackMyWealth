package com.trackmywealth.backend.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.trackmywealth.backend.config.JwtProperties;
import io.jsonwebtoken.JwtBuilder;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.UUID;
import javax.crypto.SecretKey;
import org.junit.jupiter.api.Test;

/**
 * US-02-04, #189: the MFA challenge token follows the same contract as an access token - signed
 * with the configured key, issued by the configured issuer - and is never interchangeable with one.
 * Each rejected token differs from a valid challenge token in exactly the property its test names.
 */
class MfaChallengeTokenServiceTest {

  private static final String ISSUER = "trackmywealth";
  private static final String SECRET = "test-secret-that-is-at-least-32-bytes-long";
  private static final UUID USER_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");

  private final JwtProperties properties = new JwtProperties(ISSUER, 15, 30, SECRET);
  private final MfaChallengeTokenService service = new MfaChallengeTokenService(properties);
  private final SecretKey signingKey = Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8));

  @Test
  void anIssuedChallengeTokenIsAccepted() {
    assertThat(service.parse(service.issue(USER_ID))).contains(USER_ID);
    assertThat(service.parse(validChallenge().compact())).contains(USER_ID);
  }

  @Test
  void aChallengeTokenFromAnotherIssuerIsRejected() {
    assertThat(service.parse(validChallenge().issuer("other-service").compact())).isEmpty();
  }

  @Test
  void aChallengeTokenWithoutAnIssuerIsRejected() {
    assertThat(service.parse(validChallenge().issuer(null).compact())).isEmpty();
  }

  @Test
  void aChallengeTokenWithoutASubjectIsRejectedNotAnError() {
    assertThat(service.parse(validChallenge().subject(null).compact())).isEmpty();
  }

  @Test
  void aTokenWithoutTheChallengePurposeIsRejected() {
    assertThat(service.parse(validChallenge().claim("purpose", "other").compact())).isEmpty();
    // A real access token presented here instead.
    String accessToken = new JwtService(properties).issueAccessToken(USER_ID, 1, UUID.randomUUID());
    assertThat(service.parse(accessToken)).isEmpty();
  }

  @Test
  void anExpiredChallengeTokenIsRejected() {
    Instant past = Instant.now().minus(10, ChronoUnit.MINUTES);
    String token =
        validChallenge()
            .issuedAt(Date.from(past))
            .expiration(Date.from(past.plus(5, ChronoUnit.MINUTES)))
            .compact();

    assertThat(service.parse(token)).isEmpty();
  }

  @Test
  void aChallengeTokenIsNeverAnAccessToken() {
    assertThat(new JwtService(properties).parseAccessToken(service.issue(USER_ID))).isEmpty();
  }

  private JwtBuilder validChallenge() {
    Instant now = Instant.now();
    return Jwts.builder()
        .subject(USER_ID.toString())
        .claim("purpose", "mfa_challenge")
        .issuer(ISSUER)
        .issuedAt(Date.from(now))
        .expiration(Date.from(now.plus(5, ChronoUnit.MINUTES)))
        .signWith(signingKey);
  }
}
