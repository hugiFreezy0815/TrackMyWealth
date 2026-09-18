package com.trackmywealth.backend.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Binds {@code app.security.mfa.*} (application.yml) - US-02-04. {@code encryptionKey} is a
 * Base64-encoded 32-byte AES-256 key used to encrypt {@code app_user.mfa_totp_secret} at rest
 * (NFR-SEC-001), deployment configuration like {@link JwtProperties#secret()} - never checked into
 * source.
 */
@ConfigurationProperties(prefix = "app.security.mfa")
public record MfaProperties(String encryptionKey) {}
