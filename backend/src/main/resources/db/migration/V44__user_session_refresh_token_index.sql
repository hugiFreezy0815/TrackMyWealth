-- =============================================================================================
-- V44: Index user_session by its current refresh token (US-02-02, #190)
-- =============================================================================================
-- Every refresh looks up the session by refresh_token_id (TokenRotationService), and refresh-token
-- theft detection revokes sessions whose refresh_token_id belongs to the compromised family.
-- idx_user_session_user (V16) leads with user_id, so neither lookup could use it.
-- =============================================================================================

CREATE INDEX idx_user_session_refresh_token ON user_session (refresh_token_id);
