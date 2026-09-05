package com.trackmywealth.backend.service;

import com.trackmywealth.backend.config.JwtProperties;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.UUID;
import javax.crypto.SecretKey;
import org.springframework.stereotype.Service;

/**
 * Issues short-lived JWT access tokens (FR-AUT-003). Refresh tokens are a separate, opaque,
 * server-side concept ({@code refresh_token} table, see {@link TokenIssuanceService}) - this class
 * only ever produces the self-contained, short-lived half of the pair.
 */
@Service
public class JwtService {

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

  public String issueAccessToken(UUID userId) {
    Instant now = Instant.now();
    return Jwts.builder()
        .subject(userId.toString())
        .issuer(properties.issuer())
        .issuedAt(Date.from(now))
        .expiration(Date.from(now.plus(properties.accessTokenTtlMinutes(), ChronoUnit.MINUTES)))
        .signWith(signingKey)
        .compact();
  }
}
