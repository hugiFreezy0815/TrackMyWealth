package com.trackmywealth.backend.dto;

import java.util.UUID;

/**
 * A member visible to their own workspace, without global user profile or authentication details.
 *
 * @param userId linked login user, or null for legacy members without a login (removed in #389)
 * @param active true when the workspace membership is active (independent of login status)
 */
public record WorkspaceMemberListItemResponse(
    UUID id, UUID userId, String displayName, boolean active, int version) {}
