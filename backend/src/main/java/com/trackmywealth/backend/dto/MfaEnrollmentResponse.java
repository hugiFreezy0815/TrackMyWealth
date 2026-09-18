package com.trackmywealth.backend.dto;

/**
 * Response body for {@code POST /api/v1/users/me/mfa/enroll} (US-02-04). {@code secret} (base32)
 * and {@code otpauthUri} (an {@code otpauth://} URI embedding it) are both returned so the mobile
 * client can render its own QR code from the URI, or offer manual entry of the raw secret - nothing
 * here is a rendered QR image. {@code mfa_enabled} stays {@code false} until {@code POST
 * /api/v1/users/me/mfa/confirm} succeeds with a valid code generated from this secret.
 */
public record MfaEnrollmentResponse(String secret, String otpauthUri) {}
