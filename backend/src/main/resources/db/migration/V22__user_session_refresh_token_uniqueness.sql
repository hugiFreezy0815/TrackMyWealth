-- =============================================================================================
-- V22: enforce "at most one session per refresh token"
-- =============================================================================================
-- US-02-02/US-02-03: TokenRotationService.rotate() and SessionService both rely on at most one
-- user_session row pointing at a given refresh_token at a time (rotate() looks it up via
-- findByRefreshToken_Id and repoints it; a session's revoke only ever needs to kill its own
-- current token). Nothing enforced that invariant at the database level - it held only "by
-- construction," since application code was the only thing ever setting refresh_token_id. A
-- UNIQUE constraint makes it structurally impossible to violate rather than merely unlikely to.
-- Multiple NULLs (a session detached from a deleted/never-had token, see
-- UserSessionRepository.detachRevokedRefreshTokensForUser) are still allowed - standard SQL
-- treats NULLs as distinct for uniqueness purposes.
-- =============================================================================================

ALTER TABLE user_session
    ADD CONSTRAINT uq_user_session_refresh_token_id UNIQUE (refresh_token_id);
