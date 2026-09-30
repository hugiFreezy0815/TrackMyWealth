package com.trackmywealth.backend.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Binds {@code app.security.jwt.*} (defined in {@code application.yml}).
 *
 * <p>{@code issuer} and {@code secret} must not be blank (#189): every token parser requires the
 * issuer, and JJWT's {@code requireIssuer} silently checks nothing when given none - a blank issuer
 * would turn the check off rather than reject every token. Checked here, so a misconfigured
 * deployment fails to start, and no code constructing the record directly can get around it.
 */
@ConfigurationProperties(prefix = "app.security.jwt")
public record JwtProperties(
    String issuer, int accessTokenTtlMinutes, int refreshTokenTtlDays, String secret) {

  public JwtProperties {
    if (issuer == null || issuer.isBlank()) {
      throw new IllegalArgumentException("app.security.jwt.issuer must be set.");
    }
    if (secret == null || secret.isBlank()) {
      throw new IllegalArgumentException("app.security.jwt.secret must be set.");
    }
  }
}
