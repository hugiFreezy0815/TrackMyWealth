package com.trackmywealth.backend.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Binds {@code app.security.jwt.*} (defined in {@code application.yml}). */
@ConfigurationProperties(prefix = "app.security.jwt")
public record JwtProperties(
    String issuer, int accessTokenTtlMinutes, int refreshTokenTtlDays, String secret) {}
