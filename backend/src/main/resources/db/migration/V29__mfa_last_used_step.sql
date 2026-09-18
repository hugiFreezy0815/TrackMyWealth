-- =============================================================================================
-- V29: app_user.mfa_last_used_step - TOTP replay protection (US-02-04)
-- =============================================================================================
-- RFC 6238 section 5.2: a verifier must not accept a second use of the same one-time password.
-- A TOTP code stays valid for its whole time step plus the accepted clock-skew steps (about 90
-- seconds), so without this an observed code could be replayed. The column holds the time step
-- (unix seconds / 30) of the most recently accepted code; a code is accepted only if its step is
-- strictly greater, enforced by a single conditional UPDATE so two concurrent submissions of the
-- same code cannot both win. NULL until the first accepted code, and reset to NULL whenever the
-- secret changes (new enrollment) or MFA is disabled, since a new secret starts a new code
-- stream.
-- =============================================================================================

ALTER TABLE app_user ADD COLUMN mfa_last_used_step BIGINT;
