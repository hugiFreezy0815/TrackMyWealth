package com.trackmywealth.backend.config;

import java.nio.charset.StandardCharsets;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Binds {@code app.security.jwt.*} (defined in {@code application.yml}).
 *
 * <p>{@code issuer} and {@code secret} must not be blank (#189): every token parser requires the
 * issuer, and JJWT's {@code requireIssuer} silently checks nothing when given none - a blank issuer
 * would turn the check off rather than reject every token. The signing secret must also provide at
 * least 256 bits of key material, the minimum for the HMAC-SHA family used by JJWT (#188). Checked
 * here so malformed deployment configuration fails during binding, before any token service can
 * start with it.
 */
@ConfigurationProperties(prefix = "app.security.jwt")
public record JwtProperties(
    String issuer, int accessTokenTtlMinutes, int refreshTokenTtlDays, String secret) {

  static final int MINIMUM_SIGNING_KEY_BYTES = 32;

  public JwtProperties {
    if (issuer == null || issuer.isBlank()) {
      throw new IllegalArgumentException("app.security.jwt.issuer must be set.");
    }
    if (secret == null || secret.isBlank()) {
      throw new IllegalArgumentException("JWT_SECRET must be set.");
    }
    if (secret.getBytes(StandardCharsets.UTF_8).length < MINIMUM_SIGNING_KEY_BYTES) {
      throw new IllegalArgumentException(
          "JWT_SECRET must contain at least 32 bytes of key material for JWT HMAC signing.");
    }
  }
}
