package com.trackmywealth.backend.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.trackmywealth.backend.config.JwtProperties;
import com.trackmywealth.backend.security.AccessTokenClaims;
import io.jsonwebtoken.JwtBuilder;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.Optional;
import java.util.UUID;
import javax.crypto.SecretKey;
import org.junit.jupiter.api.Test;

/**
 * FR-AUT-003/005, #189/#200: access JWTs enforce signature, issuer, lifetime, required claims and
 * explicit token type. Every rejected token below starts from the same otherwise-valid access
 * token and changes exactly the property named by the test.
 */
class JwtServiceTest {

  private static final String ISSUER = "trackmywealth";
  private static final String SECRET = "test-secret-that-is-at-least-32-bytes-long";
  private static final UUID USER_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
  private static final UUID SESSION_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");

  private final JwtProperties properties = new JwtProperties(ISSUER, 15, 30, SECRET);
  private final JwtService service = new JwtService(properties);
  private final SecretKey signingKey = Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8));

  @Test
  void issuedTokenWithExpectedIssuerTypeAndClaimsIsAccepted() {
    String token = service.issueAccessToken(USER_ID, 7, SESSION_ID);

    Optional<AccessTokenClaims> parsed = service.parseAccessToken(token);

    assertThat(parsed).contains(new AccessTokenClaims(USER_ID, 7, SESSION_ID));
  }

  @Test
  void tokenFromDifferentIssuerIsRejected() {
    assertThat(service.parseAccessToken(validAccessTokenBuilder().issuer("other-service").compact()))
        .isEmpty();
  }

  @Test
  void tokenWithoutIssuerIsRejected() {
    assertThat(service.parseAccessToken(validAccessTokenBuilder().issuer(null).compact())).isEmpty();
  }

  @Test
  void tokenWithWrongTokenTypeIsRejected() {
    assertThat(
            service.parseAccessToken(
                validAccessTokenBuilder().claim("tokenType", "mfa-challenge").compact()))
        .isEmpty();
  }

  @Test
  void tokenWithoutTokenTypeIsRejected() {
    assertThat(
            service.parseAccessToken(validAccessTokenBuilder().claim("tokenType", null).compact()))
        .isEmpty();
  }

  @Test
  void accessTokenCarryingMfaPurposeIsRejected() {
    assertThat(
            service.parseAccessToken(
                validAccessTokenBuilder().claim("purpose", "mfa_challenge").compact()))
        .isEmpty();
  }

  @Test
  void tokenWithoutSubjectIsRejected() {
    assertThat(service.parseAccessToken(validAccessTokenBuilder().subject(null).compact())).isEmpty();
  }

  @Test
  void tokenWithMalformedSubjectIsRejected() {
    assertThat(service.parseAccessToken(validAccessTokenBuilder().subject("not-a-uuid").compact()))
        .isEmpty();
  }

  @Test
  void tokenWithoutSessionIdIsRejected() {
    assertThat(
            service.parseAccessToken(validAccessTokenBuilder().claim("sessionId", null).compact()))
        .isEmpty();
  }

  @Test
  void tokenWithMalformedSessionIdIsRejected() {
    assertThat(
            service.parseAccessToken(
                validAccessTokenBuilder().claim("sessionId", "not-a-uuid").compact()))
        .isEmpty();
  }

  @Test
  void tokenWithoutTokenVersionIsRejected() {
    assertThat(
            service.parseAccessToken(
                validAccessTokenBuilder().claim("tokenVersion", null).compact()))
        .isEmpty();
  }

  @Test
  void tokenWithWrongTokenVersionTypeIsRejected() {
    assertThat(
            service.parseAccessToken(
                validAccessTokenBuilder().claim("tokenVersion", "seven").compact()))
        .isEmpty();
  }

  @Test
  void tokenWithoutIssuedAtIsRejected() {
    assertThat(service.parseAccessToken(validAccessTokenBuilder().issuedAt(null).compact())).isEmpty();
  }

  @Test
  void tokenWithoutExpirationIsRejected() {
    assertThat(service.parseAccessToken(validAccessTokenBuilder().expiration(null).compact()))
        .isEmpty();
  }

  @Test
  void expiredTokenIsRejected() {
    Instant issuedAt = Instant.now().minus(20, ChronoUnit.MINUTES);
    String token =
        validAccessTokenBuilder()
            .issuedAt(Date.from(issuedAt))
            .expiration(Date.from(issuedAt.plus(15, ChronoUnit.MINUTES)))
            .compact();

    assertThat(service.parseAccessToken(token)).isEmpty();
  }

  @Test
  void tokenSignedWithDifferentKeyIsRejected() {
    SecretKey otherKey =
        Keys.hmacShaKeyFor(
            "another-test-secret-that-is-at-least-32-bytes".getBytes(StandardCharsets.UTF_8));

    assertThat(service.parseAccessToken(validAccessTokenBuilder(otherKey).compact())).isEmpty();
  }

  @Test
  void mfaChallengeTokenIsNeverAcceptedAsAnAccessToken() {
    String challenge = new MfaChallengeTokenService(properties).issue(USER_ID);

    assertThat(service.parseAccessToken(challenge)).isEmpty();
  }

  private JwtBuilder validAccessTokenBuilder() {
    return validAccessTokenBuilder(signingKey);
  }

  private JwtBuilder validAccessTokenBuilder(SecretKey key) {
    Instant now = Instant.now();
    return Jwts.builder()
        .subject(USER_ID.toString())
        .issuer(ISSUER)
        .issuedAt(Date.from(now))
        .expiration(Date.from(now.plus(15, ChronoUnit.MINUTES)))
        .claim("tokenType", "access")
        .claim("tokenVersion", 7)
        .claim("sessionId", SESSION_ID.toString())
        .signWith(key);
  }
}
