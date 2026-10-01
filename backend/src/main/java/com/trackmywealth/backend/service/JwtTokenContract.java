package com.trackmywealth.backend.service;

/**
 * Signed JWT discriminator shared by every authentication token issued by TrackMyWealth.
 *
 * <p>Token kinds deliberately share one deployment signing key, so a cryptographically valid JWT
 * is not sufficient to establish what it is allowed to do. Every parser requires the type it owns;
 * a token for one authentication step can therefore never be accepted by another simply because
 * future stories add overlapping claims (#200, FR-AUT-003/005).
 */
final class JwtTokenContract {

  static final String TOKEN_TYPE_CLAIM = "tokenType";
  static final String ACCESS_TOKEN_TYPE = "access";
  static final String MFA_CHALLENGE_TOKEN_TYPE = "mfa-challenge";

  private JwtTokenContract() {}
}
