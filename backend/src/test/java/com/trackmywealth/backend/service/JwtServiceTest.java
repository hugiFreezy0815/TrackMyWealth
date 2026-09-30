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

class JwtServiceTest {

  private static final String ISSUER = "trackmywealth";
  private static final String SECRET = "test-secret-that-is-at-least-32-bytes-long";
  private static final UUID USER_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
  private static final UUID SESSION_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");

  private final JwtProperties properties = new JwtProperties(ISSUER, 15, 30, SECRET);
  private final JwtService service = new JwtService(properties);
  private final SecretKey signingKey = Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8));

  @Test
  void issuedTokenWithExpectedIssuerAndClaimsIsAccepted() {
    String token = service.issueAccessToken(USER_ID, 7, SESSION_ID);

    Optional<AccessTokenClaims> parsed = service.parseAccessToken(token);

    assertThat(parsed).contains(new AccessTokenClaims(USER_ID, 7, SESSION_ID));
  }

  @Test
  void tokenFromDifferentIssuerIsRejected() {
    String token =
        validTokenBuilder()
            .issuer("other-service")
            .claim("tokenVersion", 7)
            .claim("sessionId", SESSION_ID.toString())
            .compact();

    assertThat(service.parseAccessToken(token)).isEmpty();
  }

  @Test
  void tokenWithoutIssuerIsRejected() {
    Instant now = Instant.now();
    String token =
        Jwts.builder()
            .subject(USER_ID.toString())
            .issuedAt(Date.from(now))
            .expiration(Date.from(now.plus(15, ChronoUnit.MINUTES)))
            .claim("tokenVersion", 7)
            .claim("sessionId", SESSION_ID.toString())
            .signWith(signingKey)
            .compact();

    assertThat(service.parseAccessToken(token)).isEmpty();
  }

  @Test
  void tokenWithoutSubjectIsRejected() {
    Instant now = Instant.now();
    String token =
        Jwts.builder()
            .issuer(ISSUER)
            .issuedAt(Date.from(now))
            .expiration(Date.from(now.plus(15, ChronoUnit.MINUTES)))
            .claim("tokenVersion", 7)
            .claim("sessionId", SESSION_ID.toString())
            .signWith(signingKey)
            .compact();

    assertThat(service.parseAccessToken(token)).isEmpty();
  }

  @Test
  void tokenWithMalformedSubjectIsRejected() {
    String token =
        validTokenBuilder()
            .subject("not-a-uuid")
            .claim("tokenVersion", 7)
            .claim("sessionId", SESSION_ID.toString())
            .compact();

    assertThat(service.parseAccessToken(token)).isEmpty();
  }

  @Test
  void tokenWithoutSessionIdIsRejected() {
    String token = validTokenBuilder().claim("tokenVersion", 7).compact();

    assertThat(service.parseAccessToken(token)).isEmpty();
  }

  @Test
  void tokenWithMalformedSessionIdIsRejected() {
    String token =
        validTokenBuilder().claim("tokenVersion", 7).claim("sessionId", "not-a-uuid").compact();

    assertThat(service.parseAccessToken(token)).isEmpty();
  }

  @Test
  void tokenWithoutTokenVersionIsRejected() {
    String token = validTokenBuilder().claim("sessionId", SESSION_ID.toString()).compact();

    assertThat(service.parseAccessToken(token)).isEmpty();
  }

  @Test
  void tokenWithWrongTokenVersionTypeIsRejected() {
    String token =
        validTokenBuilder()
            .claim("tokenVersion", "seven")
            .claim("sessionId", SESSION_ID.toString())
            .compact();

    assertThat(service.parseAccessToken(token)).isEmpty();
  }

  @Test
  void tokenWithoutIssuedAtIsRejected() {
    Instant now = Instant.now();
    String token =
        Jwts.builder()
            .subject(USER_ID.toString())
            .issuer(ISSUER)
            .expiration(Date.from(now.plus(15, ChronoUnit.MINUTES)))
            .claim("tokenVersion", 7)
            .claim("sessionId", SESSION_ID.toString())
            .signWith(signingKey)
            .compact();

    assertThat(service.parseAccessToken(token)).isEmpty();
  }

  @Test
  void tokenWithoutExpirationIsRejected() {
    String token =
        Jwts.builder()
            .subject(USER_ID.toString())
            .issuer(ISSUER)
            .issuedAt(new Date())
            .claim("tokenVersion", 7)
            .claim("sessionId", SESSION_ID.toString())
            .signWith(signingKey)
            .compact();

    assertThat(service.parseAccessToken(token)).isEmpty();
  }

  @Test
  void expiredTokenIsRejected() {
    Instant issuedAt = Instant.now().minus(20, ChronoUnit.MINUTES);
    String token =
        Jwts.builder()
            .subject(USER_ID.toString())
            .issuer(ISSUER)
            .issuedAt(Date.from(issuedAt))
            .expiration(Date.from(issuedAt.plus(15, ChronoUnit.MINUTES)))
            .claim("tokenVersion", 7)
            .claim("sessionId", SESSION_ID.toString())
            .signWith(signingKey)
            .compact();

    assertThat(service.parseAccessToken(token)).isEmpty();
  }

  @Test
  void tokenSignedWithDifferentKeyIsRejected() {
    SecretKey otherKey =
        Keys.hmacShaKeyFor(
            "another-test-secret-that-is-at-least-32-bytes".getBytes(StandardCharsets.UTF_8));
    String token =
        Jwts.builder()
            .subject(USER_ID.toString())
            .issuer(ISSUER)
            .issuedAt(new Date())
            .expiration(Date.from(Instant.now().plus(15, ChronoUnit.MINUTES)))
            .claim("tokenVersion", 7)
            .claim("sessionId", SESSION_ID.toString())
            .signWith(otherKey)
            .compact();

    assertThat(service.parseAccessToken(token)).isEmpty();
  }

  private JwtBuilder validTokenBuilder() {
    Instant now = Instant.now();
    return Jwts.builder()
        .subject(USER_ID.toString())
        .issuer(ISSUER)
        .issuedAt(Date.from(now))
        .expiration(Date.from(now.plus(15, ChronoUnit.MINUTES)))
        .signWith(signingKey);
  }
}
