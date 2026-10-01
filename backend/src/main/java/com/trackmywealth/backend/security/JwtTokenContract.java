package com.trackmywealth.backend.security;

/**
 * Signed JWT discriminator shared by every authentication token issued by TrackMyWealth.
 *
 * <p>Token kinds deliberately share one deployment signing key, so a cryptographically valid JWT is
 * not sufficient to establish what it is allowed to do. Every parser requires the type it owns; a
 * token for one authentication step can therefore never be accepted by another simply because
 * future stories add overlapping claims (#200, FR-AUT-003/005).
 *
 * <p>The claims that belong to only one kind are named here too, so each parser can also reject the
 * other kind's claims as defence in depth: an access token never carries {@link #PURPOSE_CLAIM},
 * and an MFA challenge never carries {@link #TOKEN_VERSION_CLAIM} or {@link #SESSION_ID_CLAIM}.
 */
public final class JwtTokenContract {

  public static final String TOKEN_TYPE_CLAIM = "tokenType";
  public static final String ACCESS_TOKEN_TYPE = "access";
  public static final String MFA_CHALLENGE_TOKEN_TYPE = "mfa-challenge";

  /** Access tokens only. */
  public static final String TOKEN_VERSION_CLAIM = "tokenVersion";

  /** Access tokens only. */
  public static final String SESSION_ID_CLAIM = "sessionId";

  /** MFA challenge tokens only. */
  public static final String PURPOSE_CLAIM = "purpose";

  private JwtTokenContract() {}
}
