package com.trackmywealth.backend.dto;

import java.util.UUID;

/**
 * Response body for {@code GET /api/v1/users/me} (US-02-06): who the presented access token belongs
 * to, as the server verified it. {@code role} is what a client shows or hides admin-only screens
 * by; {@code language} is the persisted EN/DE preference; {@code sessionId} is the session the
 * token belongs to, which a client revokes on sign-out without listing all sessions first.
 */
public record CurrentUserResponse(UUID userId, String role, String language, UUID sessionId) {}
